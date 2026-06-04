package loadbalancer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Health filtering and selection delegation. */
class BackendPoolTest {

    private static List<HostPort> targets(int... ports) {
        return java.util.Arrays.stream(ports)
                .mapToObj(p -> new HostPort("127.0.0.1", p))
                .toList();
    }

    @Test
    void healthyExcludesDownBackends() {
        BackendPool pool = new BackendPool(targets(1, 2, 3), new RoundRobinStrategy());
        // all start DOWN
        assertThat(pool.healthy()).isEmpty();

        pool.all().get(0).markInitiallyHealthy();
        pool.all().get(2).markInitiallyHealthy();

        assertThat(pool.healthy()).hasSize(2);
        assertThat(pool.healthy()).noneMatch(b -> b.target().port() == 2);
    }

    @Test
    void selectReturnsNullWhenNothingIsHealthy() {
        BackendPool pool = new BackendPool(targets(1, 2), new RoundRobinStrategy());
        assertThat(pool.select()).isNull();
    }

    @Test
    void selectOnlyEverReturnsAHealthyBackend() {
        BackendPool pool = new BackendPool(targets(1, 2, 3), new RoundRobinStrategy());
        pool.all().get(1).markInitiallyHealthy(); // only port 2 is up

        for (int i = 0; i < 10; i++) {
            Backend chosen = pool.select();
            assertThat(chosen).isNotNull();
            assertThat(chosen.isHealthy()).isTrue();
            assertThat(chosen.target().port()).isEqualTo(2);
        }
    }

    @Test
    void allReturnsEveryConfiguredBackendRegardlessOfHealth() {
        BackendPool pool = new BackendPool(targets(1, 2, 3), new RoundRobinStrategy());
        assertThat(pool.all()).hasSize(3);
    }
}
