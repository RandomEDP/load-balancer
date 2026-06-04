package loadbalancer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Selection by current active-connection count. */
class LeastConnectionsStrategyTest {

    private static Backend backendWithLoad(int port, int activeConnections) {
        Backend b = new Backend(new HostPort("127.0.0.1", port));
        for (int i = 0; i < activeConnections; i++) {
            b.onConnectionOpened();
        }
        return b;
    }

    @Test
    void picksTheBackendWithFewestActiveConnections() {
        LeastConnectionsStrategy lc = new LeastConnectionsStrategy();
        Backend busy = backendWithLoad(1, 5);
        Backend idle = backendWithLoad(2, 1);
        Backend medium = backendWithLoad(3, 3);

        assertThat(lc.choose(List.of(busy, idle, medium))).isSameAs(idle);
    }

    @Test
    void onATieKeepsTheFirstSeen() {
        LeastConnectionsStrategy lc = new LeastConnectionsStrategy();
        Backend first = backendWithLoad(1, 2);
        Backend second = backendWithLoad(2, 2);

        // strict "<" comparison means the earlier element wins a tie
        assertThat(lc.choose(List.of(first, second))).isSameAs(first);
    }

    @Test
    void tracksLoadAsConnectionsOpenAndClose() {
        LeastConnectionsStrategy lc = new LeastConnectionsStrategy();
        Backend a = backendWithLoad(1, 1);
        Backend b = backendWithLoad(2, 4);

        assertThat(lc.choose(List.of(a, b))).isSameAs(a);

        // pile load onto a and drain b; the choice should flip
        a.onConnectionOpened();
        a.onConnectionOpened();
        a.onConnectionOpened();
        b.onConnectionClosed();
        b.onConnectionClosed();
        b.onConnectionClosed();
        assertThat(lc.choose(List.of(a, b))).isSameAs(b);
    }
}
