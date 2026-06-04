package loadbalancer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Probes every backend on a fixed interval by opening and immediately closing a
 * TCP connection. {@code fall} consecutive failures take a backend out of
 * rotation; {@code rise} consecutive successes put a recovered one back. The
 * two thresholds stop a backend flapping on a single transient blip.
 *
 * <p>Probes use a plain blocking connect on a dedicated scheduler, deliberately
 * kept off the Netty event loops so a slow probe can never stall request traffic.
 */
public final class HealthChecker {

    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);

    private final BackendPool pool;
    private final int timeoutMs;
    private final long intervalMs;
    private final int rise;
    private final int fall;
    private final ScheduledExecutorService scheduler;

    public HealthChecker(BackendPool pool, LbConfig config) {
        this.pool = pool;
        this.timeoutMs = config.healthTimeoutMs();
        this.intervalMs = config.healthIntervalMs();
        this.rise = config.healthRise();
        this.fall = config.healthFall();

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "health-checker");
            t.setDaemon(true);
            return t;
        };
        // small pool so a slow/hanging backend doesn't hold up the others' probes
        int threads = Math.min(8, Math.max(1, pool.all().size()));
        this.scheduler = Executors.newScheduledThreadPool(threads, tf);
    }

    /**
     * Probe every backend once, synchronously, and admit the reachable ones.
     * Call before binding the listener so traffic only flows to live backends.
     */
    public void initialProbe() {
        log.info("Running initial health probe across {} backend(s)", pool.all().size());
        for (Backend b : pool.all()) {
            if (probe(b)) {
                b.markInitiallyHealthy();
                log.info("Backend {} is UP (initial probe)", b);
            } else {
                log.warn("Backend {} is DOWN (initial probe) - excluded from rotation", b);
            }
        }
    }

    public void start() {
        // fixed delay (not fixed rate) so a backend's probes never overlap; that
        // keeps its streak counters effectively single-threaded
        for (Backend b : pool.all()) {
            scheduler.scheduleWithFixedDelay(
                    () -> check(b), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void check(Backend b) {
        try {
            if (probe(b)) {
                if (b.reportHealthSuccess(rise)) {
                    log.info("Backend {} is back UP - returned to rotation", b);
                }
            } else {
                if (b.reportHealthFailure(fall)) {
                    log.warn("Backend {} went DOWN - removed from rotation", b);
                }
            }
        } catch (Throwable t) {
            // a thrown exception here would cancel the scheduled task, so swallow it
            log.error("Health check error for {}", b, t);
        }
    }

    private boolean probe(Backend b) {
        try (Socket s = new Socket()) {
            s.connect(b.address(), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
