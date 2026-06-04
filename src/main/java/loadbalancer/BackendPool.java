package loadbalancer;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds every configured backend and selects one (from the healthy subset)
 * using the configured {@link BalancingStrategy}.
 */
public final class BackendPool {

    private final List<Backend> backends;
    private final BalancingStrategy strategy;

    public BackendPool(List<HostPort> targets, BalancingStrategy strategy) {
        List<Backend> list = new ArrayList<>(targets.size());
        for (HostPort t : targets) {
            list.add(new Backend(t));
        }
        this.backends = List.copyOf(list);
        this.strategy = strategy;
    }

    /** All backends, regardless of health (for stats / health checking). */
    public List<Backend> all() {
        return backends;
    }

    public List<Backend> healthy() {
        List<Backend> result = new ArrayList<>(backends.size());
        for (Backend b : backends) {
            if (b.isHealthy()) {
                result.add(b);
            }
        }
        return result;
    }

    /** A healthy backend chosen by the strategy, or {@code null} if none are healthy. */
    public Backend select() {
        List<Backend> healthy = healthy();
        if (healthy.isEmpty()) {
            return null;
        }
        return strategy.choose(healthy);
    }

    public BalancingStrategy strategy() {
        return strategy;
    }
}
