package dev.bugcapsule.android.play;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager;
import dev.bugcapsule.android.emergency.EmergencyAction;
import dev.bugcapsule.android.emergency.EmergencyConfig;
import dev.bugcapsule.android.emergency.EmergencyUpdateAvailability;
import dev.bugcapsule.android.emergency.EmergencyUpdateFlow;
import dev.bugcapsule.android.emergency.EmergencyUpdateProvider;
import dev.bugcapsule.android.emergency.EmergencyUpdateRequest;
import dev.bugcapsule.android.emergency.EmergencyUpdateState;
import dev.bugcapsule.android.emergency.EmergencyUpdateStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The Play provider as a Java app sets it up (compiles against the public surface only). */
@RunWith(RobolectricTestRunner.class)
public class PlayJavaInteropTest {
    @Test
    public void javaCallersBuildAndDriveTheProvider() {
        Application app = ApplicationProvider.getApplicationContext();
        FakeAppUpdateManager fake = new FakeAppUpdateManager(app);
        fake.setUpdateAvailable(57);
        PlayEmergencyUpdateProvider provider = PlayEmergencyUpdateProvider.builder(app)
                .allowImmediateUpdates(true)
                .versionCode("2.4.2", 57)
                .appUpdateManager(fake)
                .build();
        EmergencyUpdateProvider asInterface = provider;
        assertEquals(PlayEmergencyUpdateProvider.PROVIDER, asInterface.getProvider());
        assertTrue(provider.getImmediateUpdatesAllowed());
        EmergencyConfig config = EmergencyConfig.builder().updateProvider(provider).build();
        assertEquals(provider, config.getUpdateProvider());

        List<EmergencyUpdateStatus> statuses = new ArrayList<>();
        provider.setListener(statuses::add);
        List<EmergencyUpdateAvailability> answers = new ArrayList<>();
        provider.checkAvailability(new EmergencyUpdateRequest("2.4.2", EmergencyAction.REQUIRE_UPDATE, "P0"), answers::add);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(answers.get(0).getAvailable());
        assertEquals(EmergencyUpdateFlow.IMMEDIATE, answers.get(0).getFlow());
        assertEquals("2.4.2", answers.get(0).getAvailableVersion());
        assertEquals(EmergencyUpdateState.AVAILABLE, provider.status().getState());
        assertEquals(1, statuses.size());
        assertFalse(provider.completeUpdate());
        provider.onUpdateFlowResult(Activity.RESULT_CANCELED);
        assertEquals(EmergencyUpdateState.DECLINED, provider.status().getState());
        assertEquals("2.4.2", provider.status().getAvailableVersion());
    }
}
