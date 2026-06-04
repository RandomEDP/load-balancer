package loadbalancer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parsing and validation of the "host:port" backend spec. */
class HostPortTest {

    @Test
    void parsesHostAndPort() {
        HostPort hp = HostPort.parse("127.0.0.1:9001");
        assertThat(hp.host()).isEqualTo("127.0.0.1");
        assertThat(hp.port()).isEqualTo(9001);
    }

    @Test
    void trimsSurroundingWhitespace() {
        HostPort hp = HostPort.parse("  localhost : 8080 ");
        assertThat(hp.host()).isEqualTo("localhost");
        assertThat(hp.port()).isEqualTo(8080);
    }

    @Test
    void keepsLastColonSoIpv6StyleHostsKeepTheirColons() {
        // lastIndexOf(':') means only the final :port is split off
        HostPort hp = HostPort.parse("::1:9001");
        assertThat(hp.host()).isEqualTo("::1");
        assertThat(hp.port()).isEqualTo(9001);
    }

    @Test
    void toStringRoundTrips() {
        assertThat(HostPort.parse("example.com:443")).hasToString("example.com:443");
    }

    @Test
    void rejectsMissingPort() {
        assertThatThrownBy(() -> HostPort.parse("127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyPort() {
        assertThatThrownBy(() -> HostPort.parse("127.0.0.1:"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingHost() {
        assertThatThrownBy(() -> HostPort.parse(":9001"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonNumericPort() {
        assertThatThrownBy(() -> HostPort.parse("localhost:abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPortBelowRange() {
        assertThatThrownBy(() -> HostPort.parse("localhost:0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPortAboveRange() {
        assertThatThrownBy(() -> HostPort.parse("localhost:65536"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
