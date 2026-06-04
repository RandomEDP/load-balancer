package loadbalancer;

import java.util.List;

/**
 * Picks the healthy backend with the fewest active connections. Beats round-robin
 * when connection lifetimes vary a lot, since it tracks current load instead of
 * just counting requests.
 */
public final class LeastConnectionsStrategy implements BalancingStrategy {

    @Override
    public Backend choose(List<Backend> healthy) {
        Backend best = healthy.get(0);
        int bestLoad = best.activeConnections();
        for (int i = 1; i < healthy.size(); i++) {
            Backend candidate = healthy.get(i);
            int load = candidate.activeConnections();
            if (load < bestLoad) {
                best = candidate;
                bestLoad = load;
            }
        }
        return best;
    }

    @Override
    public String toString() {
        return "least-connections";
    }
}
