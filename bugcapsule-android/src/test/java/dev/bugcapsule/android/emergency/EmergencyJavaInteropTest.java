package dev.bugcapsule.android.emergency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import dev.bugcapsule.android.BugCapsule;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The emergency API as a Java app sees it (compiles against the public surface only). */
@RunWith(RobolectricTestRunner.class)
public class EmergencyJavaInteropTest {
    @Before
    public void setUp() {
        BugCapsule.resetForTest();
    }

    @After
    public void tearDown() {
        BugCapsule.resetForTest();
    }

    @Test
    public void javaCallersUseThePlainApi() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        BugCapsuleEmergency emergency = BugCapsule.getEmergency();
        assertFalse(emergency.isInitialized());

        EmergencyConfig config = EmergencyConfig.builder()
                .trustAnchor("bcek.v1.invalid-for-this-test")
                .environment("production")
                .refreshIntervalMs(60_000L)
                .failureMode("checkout.*", EmergencyFailureMode.FAIL_CLOSED)
                .failureMode("wallet.transfer", EmergencyFailureMode.USE_LAST_VALID_POLICY)
                .token(null)
                .appVersion("2.4.1")
                .build();
        assertEquals(EmergencyConfig.DEFAULT_REFRESH_INTERVAL_MS, config.getRefreshIntervalMs());
        emergency.initialize(app, config);
        assertTrue(emergency.isInitialized());

        EmergencyDecision decision = emergency.evaluate("checkout.confirm_order");
        assertFalse(decision.getAllowed());
        assertEquals(EmergencyAction.BLOCK, decision.getAction());
        assertEquals(EmergencyDecisionSource.FAILURE_MODE, decision.getSource());
        assertEquals("POLICY_UNAVAILABLE", decision.getReasonCode());
        assertNull(decision.getDocumentRevision());
        assertNull(decision.getPolicyId());
        assertFalse(decision.getFallbackAvailable());
        assertNull(decision.getPublicUserMessage());
        assertNull(decision.getRequiredVersion());
        assertNull(decision.getFallback());

        EmergencyDecision withSubject = emergency.evaluate("search.query", new EmergencyContext("user-42"));
        assertTrue(withSubject.getAllowed());
        assertTrue(emergency.isFeatureAllowed("search.query"));
        assertTrue(emergency.isFeatureAllowed("search.query", new EmergencyContext()));
        assertNull(emergency.getPolicy("search.query"));
        assertNull(emergency.getPolicy("search.query", new EmergencyContext("user-42")));

        EmergencyListener listener = snapshot -> {
            EmergencyState state = snapshot.getState();
            List<EmergencyPolicy> policies = snapshot.getPolicies();
            assertNotNull(state);
            assertNotNull(policies);
        };
        emergency.addListener(listener);
        emergency.removeListener(listener);

        Future<EmergencyStatus> refresh = emergency.refreshPolicies();
        EmergencyStatus status = refresh.get(10, TimeUnit.SECONDS);
        assertTrue(status.getInitialized());
        assertFalse(status.getTrustAnchorValid());
        assertEquals(EmergencyState.NONE, status.getState());
        assertEquals("production", status.getEnvironment());
        assertFalse(status.getCredentialRejected());
        assertNull(status.getRevision());
        assertEquals("failure_mode", EmergencyDecisionSource.FAILURE_MODE.getWire());
        assertEquals("none", emergency.status().getState().getWire());
    }
}
