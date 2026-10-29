package dev.bugcapsule.android.emergency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import dev.bugcapsule.android.BugCapsule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
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

    /** A provider for an app distributed outside Google Play, written in Java. */
    private static final class StorePageProvider implements EmergencyUpdateProvider {
        EmergencyUpdateListener listener;
        final List<EmergencyUpdateRequest> started = new ArrayList<>();

        @Override
        public String getProvider() {
            return "company_store";
        }

        @Override
        public void checkAvailability(EmergencyUpdateRequest request, EmergencyUpdateCallback callback) {
            callback.onResult(new EmergencyUpdateAvailability(
                    true,
                    EnumSet.of(EmergencyUpdateFlow.STORE_PAGE),
                    EmergencyUpdateFlow.STORE_PAGE,
                    "2.4.2",
                    request.getRequiredVersion(),
                    null,
                    null,
                    null));
        }

        @Override
        public void startUpdate(Activity activity, EmergencyUpdateRequest request) {
            started.add(request);
            listener.onUpdateStatusChanged(new EmergencyUpdateStatus(
                    getProvider(), EmergencyUpdateFlow.STORE_PAGE, EmergencyUpdateState.OPENED_STORE, "2.4.2", request.getRequiredVersion()));
        }

        @Override
        public void resumeUpdate(Activity activity) {
        }

        @Override
        public boolean completeUpdate() {
            return false;
        }

        @Override
        public void setListener(EmergencyUpdateListener listener) {
            this.listener = listener;
        }
    }

    @Test
    public void javaCallersUseTheUpdateApi() {
        Application app = ApplicationProvider.getApplicationContext();
        BugCapsuleEmergency emergency = BugCapsule.getEmergency();
        EmergencyUpdates updates = emergency.getUpdates();
        assertFalse(updates.isConfigured());

        StorePageProvider provider = new StorePageProvider();
        EmergencyConfig config = EmergencyConfig.builder()
                .trustAnchor("bcek.v1.invalid-for-this-test")
                .updateProvider(provider)
                .build();
        assertEquals(provider, config.getUpdateProvider());
        emergency.initialize(app, config);
        assertTrue(updates.isConfigured());
        assertEquals("company_store", updates.getProviderName());

        List<EmergencyUpdateAvailability> answers = new ArrayList<>();
        updates.checkForUpdate(new EmergencyUpdateRequest("2.4.2"), answers::add);
        EmergencyDecision decision = emergency.evaluate("checkout.confirm_order");
        assertNull(decision.getSeverity());
        updates.checkForUpdate(decision, answers::add);
        List<EmergencyUpdateStatus> statuses = new ArrayList<>();
        EmergencyUpdateListener listener = statuses::add;
        updates.addListener(listener);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        updates.startUpdate(activity, new EmergencyUpdateRequest("2.4.2", EmergencyAction.REQUIRE_UPDATE, "P0"));
        updates.startUpdate(activity, decision);
        updates.resumeUpdate(activity);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(2, answers.size());
        assertTrue(answers.get(0).getAvailable());
        assertEquals(Collections.singleton(EmergencyUpdateFlow.STORE_PAGE), answers.get(0).getAllowedFlows());
        assertEquals("2.4.2", answers.get(0).getRequiredVersion());
        assertNull(answers.get(0).getError());
        assertNull(answers.get(0).getStalenessDays());
        assertEquals(2, statuses.size());
        assertEquals(EmergencyUpdateState.OPENED_STORE, statuses.get(0).getState());
        assertEquals("opened_store", statuses.get(0).getState().getWire());
        assertEquals("store_page", statuses.get(0).getFlow().getWire());
        assertEquals(0L, statuses.get(0).getBytesDownloaded());
        assertEquals(EmergencyUpdateState.OPENED_STORE, updates.status().getState());
        assertTrue(provider.started.get(0).isUrgent());
        assertFalse(provider.started.get(1).isUrgent());
        assertFalse(EmergencyUpdateRequest.from(decision).isUrgent());
        assertNull(new EmergencyUpdateRequest().getRequiredVersion());
        assertFalse(new EmergencyUpdateAvailability(false).getAvailable());
        assertFalse(updates.completeUpdate());
        updates.removeListener(listener);
    }
}
