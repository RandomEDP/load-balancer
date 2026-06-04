package demo;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP backend for demoing the balancer. Each instance reports its own id,
 * so hitting the balancer repeatedly visibly cycles through backends. The
 * balancer is layer 4 and doesn't care that this speaks HTTP; it just makes the
 * demo easy to poke with curl or a browser.
 *
 * Usage: java demo.TestBackend <port> [id]
 */
public final class TestBackend {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java demo.TestBackend <port> [id]");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        String id = args.length > 1 ? args[1] : "backend-" + port;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            String body = "Handled by " + id + " (port " + port + ")\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().add("X-Backend-Id", id);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("TestBackend '" + id + "' listening on http://127.0.0.1:" + port);
    }
}
