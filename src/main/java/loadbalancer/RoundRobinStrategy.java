package loadbalancer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cycles through the healthy backends in order. The caller passes a fresh
 * snapshot each time, so the rotation keeps working as backends come and go.
 */
public final class RoundRobinStrategy implements BalancingStrategy {

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Backend choose(List<Backend> healthy) {
        // mask off the sign bit so the counter wrapping past Integer.MAX_VALUE
        // doesn't hand us a negative index
        int idx = (counter.getAndIncrement() & Integer.MAX_VALUE) % healthy.size();
        return healthy.get(idx);
    }

    @Override
    public String toString() {
        return "round-robin";
    }
}
