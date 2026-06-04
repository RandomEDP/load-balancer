package loadbalancer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Rotation order, single-backend behaviour, and integer-wraparound safety. */
class RoundRobinStrategyTest {

    private static Backend backend(int port) {
        return new Backend(new HostPort("127.0.0.1", port));
    }

    @Test
    void cyclesThroughBackendsInOrderAndWrapsAround() {
        RoundRobinStrategy rr = new RoundRobinStrategy();
        List<Backend> pool = List.of(backend(1), backend(2), backend(3));

        assertThat(rr.choose(pool)).isSameAs(pool.get(0));
        assertThat(rr.choose(pool)).isSameAs(pool.get(1));
        assertThat(rr.choose(pool)).isSameAs(pool.get(2));
        assertThat(rr.choose(pool)).isSameAs(pool.get(0)); // wrapped
    }

    @Test
    void alwaysReturnsTheOnlyBackendWhenPoolHasOne() {
        RoundRobinStrategy rr = new RoundRobinStrategy();
        List<Backend> pool = List.of(backend(1));
        for (int i = 0; i < 5; i++) {
            assertThat(rr.choose(pool)).isSameAs(pool.get(0));
        }
    }

    @Test
    void distributesEvenlyOverManyPicks() {
        RoundRobinStrategy rr = new RoundRobinStrategy();
        Backend a = backend(1), b = backend(2), c = backend(3);
        List<Backend> pool = List.of(a, b, c);

        int aCount = 0, bCount = 0, cCount = 0;
        for (int i = 0; i < 300; i++) {
            Backend chosen = rr.choose(pool);
            if (chosen == a) aCount++;
            else if (chosen == b) bCount++;
            else cCount++;
        }
        assertThat(aCount).isEqualTo(100);
        assertThat(bCount).isEqualTo(100);
        assertThat(cCount).isEqualTo(100);
    }

    @Test
    void doesNotReturnNegativeIndexWhenCounterWrapsPastIntegerMaxValue() throws Exception {
        RoundRobinStrategy rr = new RoundRobinStrategy();
        // Force the internal counter to the brink of overflow so getAndIncrement()
        // returns Integer.MAX_VALUE and then a negative number on the next call.
        Field counterField = RoundRobinStrategy.class.getDeclaredField("counter");
        counterField.setAccessible(true);
        ((AtomicInteger) counterField.get(rr)).set(Integer.MAX_VALUE);

        List<Backend> pool = List.of(backend(1), backend(2), backend(3));
        // These two calls span the overflow; neither may throw IndexOutOfBounds.
        assertThat(pool).contains(rr.choose(pool));
        assertThat(pool).contains(rr.choose(pool));
    }
}
