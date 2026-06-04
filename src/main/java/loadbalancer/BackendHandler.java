package loadbalancer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * The backend->client half of the relay. Reads bytes from the backend and writes
 * them to the client channel, pulling the next chunk only once the previous write
 * has flushed (that read-after-write step is the backpressure).
 */
final class BackendHandler extends ChannelInboundHandlerAdapter {

    private final Channel client;

    BackendHandler(Channel client) {
        this.client = client;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read(); // AUTO_READ is off, so kick off the first read explicitly
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        client.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                ctx.channel().read(); // client kept up; read more from the backend
            } else {
                future.channel().close();
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // backend closed: flush whatever is pending to the client, then close it
        FrontendHandler.closeOnFlush(client);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        FrontendHandler.closeOnFlush(ctx.channel());
    }
}
