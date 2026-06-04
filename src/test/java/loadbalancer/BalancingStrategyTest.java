package loadbalancer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Strategy lookup by name, including the accepted aliases. */
class BalancingStrategyTest {

    @Test
    void resolvesRoundRobinAndItsAliases() {
        assertThat(BalancingStrategy.fromName("round-robin")).isInstanceOf(RoundRobinStrategy.class);
        assertThat(BalancingStrategy.fromName("roundrobin")).isInstanceOf(RoundRobinStrategy.class);
        assertThat(BalancingStrategy.fromName("rr")).isInstanceOf(RoundRobinStrategy.class);
    }

    @Test
    void resolvesLeastConnectionsAndItsAliases() {
        assertThat(BalancingStrategy.fromName("least-connections")).isInstanceOf(LeastConnectionsStrategy.class);
        assertThat(BalancingStrategy.fromName("leastconnections")).isInstanceOf(LeastConnectionsStrategy.class);
        assertThat(BalancingStrategy.fromName("lc")).isInstanceOf(LeastConnectionsStrategy.class);
    }

    @Test
    void isCaseInsensitiveAndTrimmed() {
        assertThat(BalancingStrategy.fromName("  Round-Robin  ")).isInstanceOf(RoundRobinStrategy.class);
    }

    @Test
    void defaultsToRoundRobinForBlankOrNull() {
        assertThat(BalancingStrategy.fromName("")).isInstanceOf(RoundRobinStrategy.class);
        assertThat(BalancingStrategy.fromName(null)).isInstanceOf(RoundRobinStrategy.class);
    }

    @Test
    void rejectsUnknownStrategy() {
        assertThatThrownBy(() -> BalancingStrategy.fromName("random"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("random");
    }
}
