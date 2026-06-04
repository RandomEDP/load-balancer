package loadbalancer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;

/**
 * The client-facing half of the relay, one instance per accepted connection.
 *
 * <p>On connect it picks a healthy backend and dials it, failing over to the next
 * backend if the connect fails (a connect failure also counts against that
 * backend's health, so one that died between probes drops out promptly). Once a
 * backend is connected it relays client->backend bytes, reading the next chunk
 * only after the previous write flushes. A {@link Semaphore} caps how many
 * connections we serve at once; over the cap, new connections are rejected
 * rather than blocking an event-loop thread.
 */
final class FrontendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(FrontendHandler.class);

    private final BackendPool pool;
    private final Semaphore limiter;
    private final int connectTimeoutMs;
    private final int healthFall;

    private Channel backendChannel;
    private Backend backend;
    private boolean permitHeld;

    FrontendHandler(BackendPool pool, Semaphore limiter, LbConfig config) {
        this.pool = pool;
        this.limiter = limiter;
        this.connectTimeoutMs = config.connectTimeoutMs();
        this.healthFall = config.healthFall();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel client = ctx.channel();
        if (!limiter.tryAcquire()) {
            log.warn("Connection cap reached - rejecting {}", client.remoteAddress());
            client.close();
            return;
        }
        permitHeld = true;
        connectWithFailover(ctx, pool.all().size());
    }

    private void connectWithFailover(ChannelHandlerContext ctx, int attemptsLeft) {
        Channel client = ctx.channel();
        Backend b = pool.select();
        if (b == null || attemptsLeft <= 0) {
            log.warn("No healthy backend available for {} - dropping", client.remoteAddress());
            closeOnFlush(client);
            return;
        }

        Bootstrap bootstrap = new Bootstrap()
                .group(client.eventLoop()) // reuse the client's event loop, no extra threads
                .channel(NioSocketChannel.class)
                .option(ChannelOption.AUTO_READ, false)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .handler(new BackendHandler(client));

        bootstrap.connect(b.address()).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                if (!client.isActive()) {
                    f.channel().close(); // client gave up while we were connecting
                    return;
                }
                backend = b;
                backendChannel = f.channel();
                b.onConnectionOpened();
                log.info("Routed {} -> {} (active={})", client.remoteAddress(), b, b.activeConnections());
                client.read(); // backend ready - start pulling client bytes
            } else {
                b.onConnectFailure();
                if (b.reportHealthFailure(healthFall)) {
                    log.warn("Backend {} went DOWN (connect failed) - removed from rotation", b);
                }
                log.warn("Connect to {} failed ({}), trying next backend", b, f.cause().getMessage());
                connectWithFailover(ctx, attemptsLeft - 1);
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (backendChannel != null && backendChannel.isActive()) {
            backendChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    ctx.channel().read(); // backend kept up; read more from the client
                } else {
                    future.channel().close();
                }
            });
        } else {
            ReferenceCountUtil.release(msg); // no backend yet (or already gone)
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        // Client finished sending (received its FIN). Half-close the backend's
        // write side so it sees end-of-request, but keep relaying its response
        // back. AUTO_READ stays driven by the response pump.
        if (evt instanceof ChannelInputShutdownEvent) {
            if (backendChannel instanceof DuplexChannel dc && backendChannel.isActive()) {
                dc.shutdownOutput();
            }
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (backendChannel != null) {
            closeOnFlush(backendChannel);
        }
        releaseResources();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Client {} error: {}", ctx.channel().remoteAddress(), cause.getMessage());
        closeOnFlush(ctx.channel());
    }

    private void releaseResources() {
        if (backend != null) {
            backend.onConnectionClosed();
            backend = null;
        }
        if (permitHeld) {
            limiter.release();
            permitHeld = false;
        }
    }

    /** Flush any pending writes, then close. Avoids truncating a half-sent response. */
    static void closeOnFlush(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
