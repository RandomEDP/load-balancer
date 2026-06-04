package loadbalancer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Wires up and runs the Netty server: one boss group for accepts, one worker
 * group running the event loops that drive every connection's relay. Netty
 * supplies the accept loop, threading, byte relay, backpressure and half-close;
 * this class just configures them and owns startup/shutdown plus health checks.
 */
public final class LoadBalancerServer {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancerServer.class);
    private static final int ACCEPT_BACKLOG = 1024;

    private final LbConfig config;
    private final BackendPool pool;
    private final HealthChecker health;
    private final Semaphore limiter;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public LoadBalancerServer(LbConfig config) {
        this.config = config;
        this.pool = new BackendPool(config.backends(), BalancingStrategy.fromName(config.strategy()));
        this.health = new HealthChecker(pool, config);
        this.limiter = new Semaphore(config.maxConnections());
    }

    /** Probe backends, bind the listener, start accepting. Returns the bound port. */
    public int start() throws InterruptedException {
        log.info("Starting load balancer (strategy={}, maxConnections={})",
                pool.strategy(), config.maxConnections());

        health.initialProbe();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, ACCEPT_BACKLOG)
                .option(ChannelOption.SO_REUSEADDR, true)
                // Hand reads to FrontendHandler: it enables reading only once a
                // backend is connected, and after that pulls one chunk per flush.
                .childOption(ChannelOption.AUTO_READ, false)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                // A client that finishes sending (FIN) must not tear the connection
                // down; we half-close the backend instead so its response still flows
                // back. Without this, a half-closing client truncates its own response.
                .childOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (config.idleTimeoutMs() > 0) {
                            ch.pipeline().addLast(new ReadTimeoutHandler(
                                    config.idleTimeoutMs(), TimeUnit.MILLISECONDS));
                        }
                        ch.pipeline().addLast(new FrontendHandler(pool, limiter, config));
                    }
                });

        serverChannel = bootstrap.bind(config.host(), config.port()).sync().channel();
        health.start();
        startStatsReporter();

        int boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        log.info("Listening on {}:{}", config.host(), boundPort);
        return boundPort;
    }

    /** Blocks until the server channel closes (used by Main to keep the process alive). */
    public void awaitShutdown() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.closeFuture().sync();
        }
    }

    /** Stops accepting and shuts down the event loops and health checks. Idempotent. */
    public void stop() {
        log.info("Shutting down load balancer");
        health.stop();
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    public int boundPort() {
        return serverChannel == null ? -1 : ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public BackendPool pool() {
        return pool;
    }

    private void startStatsReporter() {
        if (config.statsIntervalMs() <= 0) {
            return;
        }
        bossGroup.scheduleWithFixedDelay(this::logStats,
                config.statsIntervalMs(), config.statsIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void logStats() {
        StringBuilder sb = new StringBuilder("stats: free-slots=")
                .append(limiter.availablePermits()).append(" | backends:");
        for (Backend b : pool.all()) {
            sb.append(' ').append(b).append('[')
                    .append(b.isHealthy() ? "UP" : "DOWN")
                    .append(" active=").append(b.activeConnections())
                    .append(" total=").append(b.totalConnections())
                    .append(" fails=").append(b.connectFailures())
                    .append(']');
        }
        log.info(sb.toString());
    }
}
