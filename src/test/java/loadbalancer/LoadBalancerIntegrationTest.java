package loadbalancer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives a real {@link LoadBalancerServer} (Netty) in front of three in-process
 * TCP backends and checks the core behaviour: even spread, removal of an offline
 * backend, and re-admission once it recovers.
 */
class LoadBalancerIntegrationTest {

    private final List<MiniTcpServer> backends = new ArrayList<>();
    private LoadBalancerServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        backends.forEach(MiniTcpServer::stop);
    }

    @Test
    void balancesTrafficAndHandlesBackendGoingOfflineThenRecovering() throws Exception {
        MiniTcpServer b1 = startBackend("B1");
        MiniTcpServer b2 = startBackend("B2");
        MiniTcpServer b3 = startBackend("B3");

        server = new LoadBalancerServer(config(b1.port(), b2.port(), b3.port()));
        int lbPort = server.start();

        assertThat(server.pool().healthy()).hasSize(3);

        // even spread across all three
        Map<String, Integer> spread = hitMany(lbPort, 30);
        assertThat(spread.keySet()).containsExactlyInAnyOrder("B1", "B2", "B3");
        int max = spread.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        int min = spread.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
        assertThat(max - min).as("round-robin should be even").isLessThanOrEqualTo(1);

        // take B2 offline; the health checker should drop it from rotation
        b2.stop();
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !isHealthy(b2.port()));
        assertThat(server.pool().healthy()).hasSize(2);

        Map<String, Integer> afterOffline = hitMany(lbPort, 20);
        assertThat(afterOffline).doesNotContainKey("B2");
        assertThat(afterOffline.getOrDefault("B1", 0) + afterOffline.getOrDefault("B3", 0))
                .isEqualTo(20);

        // bring B2 back; it should rejoin rotation
        MiniTcpServer b2again = startBackend("B2", b2.port());
        await().atMost(Duration.ofSeconds(5))
                .until(() -> isHealthy(b2again.port()));
        assertThat(server.pool().healthy()).hasSize(3);
    }

    private LbConfig config(int... ports) {
        List<HostPort> targets = new ArrayList<>();
        for (int p : ports) {
            targets.add(new HostPort("127.0.0.1", p));
        }
        return new LbConfig("127.0.0.1", 0, targets, "round-robin",
                200, 200, 2, 2, 500, 1000, 0, 0);
    }

    private boolean isHealthy(int port) {
        return server.pool().all().stream()
                .anyMatch(b -> b.target().port() == port && b.isHealthy());
    }

    private Map<String, Integer> hitMany(int lbPort, int n) throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < n; i++) {
            counts.merge(request(lbPort), 1, Integer::sum);
        }
        return counts;
    }

    /** Opens one connection through the balancer and returns the backend id that answered. */
    private String request(int lbPort) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", lbPort), 2000);
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            out.write("PING\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            s.shutdownOutput();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[256];
            int r;
            while ((r = in.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toString(StandardCharsets.UTF_8).trim();
        }
    }

    private MiniTcpServer startBackend(String id) throws IOException {
        return startBackend(id, 0);
    }

    private MiniTcpServer startBackend(String id, int port) throws IOException {
        MiniTcpServer s = new MiniTcpServer(id, port);
        s.start();
        backends.add(s);
        return s;
    }

    /**
     * A minimal blocking TCP backend: reads the request, replies with its id, and
     * half-closes. Plain blocking I/O on purpose - the point is to exercise the
     * balancer, so the test double stays trivial.
     */
    static final class MiniTcpServer {
        private final String id;
        private final ServerSocket ss;
        private volatile boolean running = true;

        MiniTcpServer(String id, int port) throws IOException {
            this.id = id;
            this.ss = new ServerSocket();
            this.ss.setReuseAddress(true);
            this.ss.bind(new InetSocketAddress("127.0.0.1", port));
        }

        int port() {
            return ss.getLocalPort();
        }

        void start() {
            Thread t = new Thread(this::loop, "mini-" + id);
            t.setDaemon(true);
            t.start();
        }

        private void loop() {
            while (running) {
                final Socket c;
                try {
                    c = ss.accept();
                } catch (IOException e) {
                    break; // socket closed
                }
                Thread worker = new Thread(() -> serve(c), "mini-" + id + "-conn");
                worker.setDaemon(true);
                worker.start();
            }
        }

        private void serve(Socket c) {
            try (Socket s = c) {
                InputStream in = s.getInputStream();
                in.read(new byte[256]); // consume the request
                OutputStream out = s.getOutputStream();
                out.write((id + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                s.shutdownOutput();
            } catch (IOException ignored) {
                // client gone
            }
        }

        void stop() {
            running = false;
            try {
                ss.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
