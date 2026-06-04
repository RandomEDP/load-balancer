package loadbalancer;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One backend server and its live state: health flag, active connection count,
 * and lifetime counters for balancing and stats.
 */
public final class Backend {

    private final HostPort target;
    private final InetSocketAddress address;

    // start DOWN; the first successful health check brings it up
    private volatile boolean healthy = false;

    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong connectFailures = new AtomicLong();

    // streak counters, only touched inside the synchronized report* methods
    private int consecutiveSuccesses;
    private int consecutiveFailures;

    public Backend(HostPort target) {
        this.target = target;
        this.address = target.toSocketAddress();
    }

    public HostPort target() {
        return target;
    }

    public InetSocketAddress address() {
        return address;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public long totalConnections() {
        return totalConnections.get();
    }

    public long connectFailures() {
        return connectFailures.get();
    }

    public void onConnectionOpened() {
        activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
    }

    public void onConnectionClosed() {
        activeConnections.decrementAndGet();
    }

    public void onConnectFailure() {
        connectFailures.incrementAndGet();
    }

    // Startup probe puts a reachable backend straight into service, skipping the
    // usual rise threshold.
    public synchronized void markInitiallyHealthy() {
        healthy = true;
        consecutiveSuccesses = 0;
        consecutiveFailures = 0;
    }

    /** Records a successful probe. Returns true if the backend just came UP. */
    public synchronized boolean reportHealthSuccess(int rise) {
        consecutiveFailures = 0;
        if (healthy) {
            return false;
        }
        consecutiveSuccesses++;
        if (consecutiveSuccesses >= rise) {
            consecutiveSuccesses = 0;
            healthy = true;
            return true;
        }
        return false;
    }

    /** Records a failed probe (or a connect failure on live traffic). Returns true if it just went DOWN. */
    public synchronized boolean reportHealthFailure(int fall) {
        consecutiveSuccesses = 0;
        if (!healthy) {
            return false;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= fall) {
            consecutiveFailures = 0;
            healthy = false;
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return target.toString();
    }
}
