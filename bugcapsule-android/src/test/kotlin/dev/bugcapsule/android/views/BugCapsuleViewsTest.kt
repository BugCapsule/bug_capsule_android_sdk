package dev.bugcapsule.android.views

import android.app.Application
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.CapsuleCodec
import dev.bugcapsule.android.internal.BugCapsuleTestOverrides
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BugCapsuleViewsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun reset() {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = null
    }

    @Test
    fun actionsTextAndForcedSensitivePasswords() {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root)
        BugCapsule.init(app, BugCapsuleConfig.builder().mode(CaptureMode.QA).build())
        var clicks = 0
        val button = Button(app)
        BugCapsuleViews.onClick(button, "cart.checkout") { clicks++ }
        button.performClick()
        assertTrue("semantic replay invokes it by id", BugCapsule.invokeAction("cart.checkout"))
        assertEquals(2, clicks)

        val coupon = EditText(app)
        BugCapsuleViews.trackText(coupon, "cart.coupon")
        coupon.setText("SAVE10")
        val password = EditText(app).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        assertTrue(BugCapsuleViews.isPasswordInput(password))
        BugCapsuleViews.trackText(password, "field1") // innocuous id: still sensitive
        password.setText("hunter2")
        assertTrue(BugCapsule.setTrackedText("cart.coupon", "SAVE100"))
        assertEquals("SAVE100", coupon.text.toString())

        BugCapsule.fail("x")
        val deadline = System.currentTimeMillis() + 10_000
        while (BugCapsule.savedCaptures().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val capsule = CapsuleCodec.decode(BugCapsule.savedCaptures().first().readBytes())
        val texts = capsule.events.filter { it.type == "text" }.map { it.data["id"] to it.data["value"] }
        assertEquals(listOf<Pair<Any?, Any?>>("cart.coupon" to "SAVE10", "field1" to "[REDACTED]"), texts)
        assertEquals(listOf("cart.checkout"), capsule.events.filter { it.type == "action" }.map { it.data["id"] })
    }
}
