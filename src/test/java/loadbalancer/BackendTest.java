package loadbalancer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rise/fall health state machine and the connection counters. The return
 * value of the report* methods signals an UP/DOWN transition (used for logging),
 * so it must be true only on the crossing probe.
 */
class BackendTest {

    private static Backend backend() {
        return new Backend(new HostPort("127.0.0.1", 9001));
    }

    @Test
    void startsDown() {
        assertThat(backend().isHealthy()).isFalse();
    }

    @Test
    void comesUpOnlyAfterRiseConsecutiveSuccesses() {
        Backend b = backend();
        int rise = 2;

        assertThat(b.reportHealthSuccess(rise)).isFalse(); // 1st success, not yet
        assertThat(b.isHealthy()).isFalse();
        assertThat(b.reportHealthSuccess(rise)).isTrue();  // 2nd: transitions UP
        assertThat(b.isHealthy()).isTrue();
    }

    @Test
    void furtherSuccessesAfterUpReportNoTransition() {
        Backend b = backend();
        b.reportHealthSuccess(1); // up after one
        assertThat(b.isHealthy()).isTrue();
        assertThat(b.reportHealthSuccess(1)).isFalse();
        assertThat(b.reportHealthSuccess(1)).isFalse();
    }

    @Test
    void goesDownOnlyAfterFallConsecutiveFailures() {
        Backend b = backend();
        b.markInitiallyHealthy();
        int fall = 3;

        assertThat(b.reportHealthFailure(fall)).isFalse(); // 1
        assertThat(b.reportHealthFailure(fall)).isFalse(); // 2
        assertThat(b.isHealthy()).isTrue();
        assertThat(b.reportHealthFailure(fall)).isTrue();  // 3: transitions DOWN
        assertThat(b.isHealthy()).isFalse();
    }

    @Test
    void aSuccessResetsAPartialFailureStreak() {
        Backend b = backend();
        b.markInitiallyHealthy();
        int fall = 3;

        b.reportHealthFailure(fall);            // failure streak = 1
        b.reportHealthFailure(fall);            // failure streak = 2
        b.reportHealthSuccess(2);               // resets the failure streak
        assertThat(b.reportHealthFailure(fall)).isFalse(); // streak restarts at 1
        assertThat(b.reportHealthFailure(fall)).isFalse(); // 2
        assertThat(b.isHealthy()).isTrue();
    }

    @Test
    void aFailureResetsAPartialRiseStreak() {
        Backend b = backend();
        int rise = 2;

        assertThat(b.reportHealthSuccess(rise)).isFalse(); // success streak = 1
        b.reportHealthFailure(3);                          // resets it (still down)
        assertThat(b.reportHealthSuccess(rise)).isFalse(); // streak restarts at 1
        assertThat(b.reportHealthSuccess(rise)).isTrue();  // now UP
    }

    @Test
    void reportingFailureWhileAlreadyDownIsANoOp() {
        Backend b = backend();
        assertThat(b.reportHealthFailure(3)).isFalse();
        assertThat(b.isHealthy()).isFalse();
    }

    @Test
    void markInitiallyHealthyBringsItUpImmediately() {
        Backend b = backend();
        b.markInitiallyHealthy();
        assertThat(b.isHealthy()).isTrue();
    }

    @Test
    void connectionCountersTrackOpenCloseAndFailures() {
        Backend b = backend();
        b.onConnectionOpened();
        b.onConnectionOpened();
        assertThat(b.activeConnections()).isEqualTo(2);
        assertThat(b.totalConnections()).isEqualTo(2);

        b.onConnectionClosed();
        assertThat(b.activeConnections()).isEqualTo(1);
        assertThat(b.totalConnections()).isEqualTo(2); // total is lifetime, never decremented

        b.onConnectFailure();
        assertThat(b.connectFailures()).isEqualTo(1);
    }
}
