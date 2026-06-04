package loadbalancer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Construction-time validation of the immutable config record. */
class LbConfigTest {

    private static LbConfig config(List<HostPort> backends, String strategy) {
        return new LbConfig("0.0.0.0", 8080, backends, strategy,
                2000, 1000, 2, 3, 2000, 1000, 0, 0);
    }

    @Test
    void acceptsAValidConfiguration() {
        LbConfig cfg = config(List.of(new HostPort("127.0.0.1", 9001)), "round-robin");
        assertThat(cfg.backends()).hasSize(1);
        assertThat(cfg.strategy()).isEqualTo("round-robin");
    }

    @Test
    void rejectsAnEmptyBackendList() {
        assertThatThrownBy(() -> config(List.of(), "round-robin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullBackends() {
        assertThatThrownBy(() -> config(null, "round-robin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnUnknownStrategyUpFront() {
        assertThatThrownBy(() -> config(List.of(new HostPort("127.0.0.1", 9001)), "bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void backendsListIsDefensivelyCopied() {
        List<HostPort> mutable = new java.util.ArrayList<>();
        mutable.add(new HostPort("127.0.0.1", 9001));
        LbConfig cfg = config(mutable, "round-robin");

        mutable.add(new HostPort("127.0.0.1", 9002));
        assertThat(cfg.backends()).hasSize(1); // unaffected by later mutation
    }
}
