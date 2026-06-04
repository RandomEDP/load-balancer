package loadbalancer;

import java.util.List;

/**
 * Immutable runtime config. picocli ({@link Main}) parses the CLI and an optional
 * properties file into this record; nothing here does any parsing itself.
 */
public record LbConfig(
        String host,
        int port,
        List<HostPort> backends,
        String strategy,
        long healthIntervalMs,
        int healthTimeoutMs,
        int healthRise,
        int healthFall,
        int connectTimeoutMs,
        int maxConnections,
        int idleTimeoutMs,
        long statsIntervalMs) {

    public LbConfig {
        if (backends == null || backends.isEmpty()) {
            throw new IllegalArgumentException("At least one backend is required (--backends host:port,...)");
        }
        // fail fast on a bad strategy name rather than on the first connection
        BalancingStrategy.fromName(strategy);
        backends = List.copyOf(backends);
    }
}
