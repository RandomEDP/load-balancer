package loadbalancer;

import java.util.List;

/** Strategy for choosing one backend from the set of currently-healthy backends. */
public interface BalancingStrategy {

    /**
     * @param healthy non-empty list of healthy backends (the caller guarantees it is non-empty)
     * @return the chosen backend
     */
    Backend choose(List<Backend> healthy);

    static BalancingStrategy fromName(String name) {
        String n = name == null ? "" : name.trim().toLowerCase();
        return switch (n) {
            case "", "round-robin", "roundrobin", "rr" -> new RoundRobinStrategy();
            case "least-connections", "leastconnections", "lc" -> new LeastConnectionsStrategy();
            default -> throw new IllegalArgumentException("Unknown strategy '" + name
                    + "', expected 'round-robin' or 'least-connections'");
        };
    }
}
