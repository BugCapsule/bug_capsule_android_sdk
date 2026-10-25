package dev.bugcapsule.demo

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.core.CapsuleCodec
import org.hamcrest.CoreMatchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Views capture on a real device: pointers through the wrapped Window.Callback,
 * semantic actions, tracked text, Jetpack Navigation routes, Retrofit/OkHttp
 * exchanges, registered ViewModel state and an automatic assertion capsule.
 */
@RunWith(AndroidJUnit4::class)
class ViewsCaptureTest {
    @Test
    fun couponAssertionIsCapturedAutomatically() {
        val before = BugCapsule.savedCaptures().map { it.name }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The fake server returns random carts: SAVE100 only breaks carts under 100.
            for (attempt in 1..15) {
                onView(withId(R.id.open_cart)).perform(click())
                lateinit var status: android.widget.TextView
                scenario.onActivity { status = it.findViewById(R.id.cart_status) }
                val ready = object : androidx.test.espresso.IdlingResource, android.text.TextWatcher {
                    var callback: androidx.test.espresso.IdlingResource.ResourceCallback? = null
                    override fun getName() = "cart response ready"
                    override fun isIdleNow() = status.text.contains("ready")
                    override fun registerIdleTransitionCallback(cb: androidx.test.espresso.IdlingResource.ResourceCallback) { callback = cb }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        if (isIdleNow) callback?.onTransitionToIdle()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                }
                scenario.onActivity { status.addTextChangedListener(ready) }
                androidx.test.espresso.IdlingRegistry.getInstance().register(ready)
                try {
                    onView(withId(R.id.cart_status)).check(matches(withText(containsString("ready"))))
                } finally {
                    androidx.test.espresso.IdlingRegistry.getInstance().unregister(ready)
                    scenario.onActivity { status.removeTextChangedListener(ready) }
                }
                var total = 0.0
                scenario.onActivity { a ->
                    total = a.findViewById<android.widget.TextView>(R.id.total).text.toString()
                        .removePrefix("Total: ").substringBefore(' ').toDouble()
                }
                if (total < 100) break
                pressBack()
            }
            onView(withId(R.id.coupon)).perform(typeText("SAVE100"), click(), closeSoftKeyboard())
            onView(withId(R.id.apply_coupon)).perform(click())
            val file = awaitNewCapture(before)
            val capsule = CapsuleCodec.decode(file.readBytes())
            val m = capsule.manifest
            assertEquals("assertion:cart_total_non_negative:", m.failure!!.signature)
            assertEquals("android", m.platform.os)
            assertEquals(listOf("okhttp", "navigation", "shared_preferences", "datastore"), m.integrations)
            val types = capsule.events.map { it.type }.toSet()
            assertTrue(types.toString(), types.containsAll(setOf("pointer", "action", "text", "route", "http", "state", "lifecycle", "failure")))
            val pointers = capsule.events.filter { it.type == "pointer" }
            assertTrue("pointer events retain keyboard visibility", pointers.all { it.data["imeVisible"] is Boolean })
            assertEquals(false, pointers.last { it.data["phase"] == "down" }.data["imeVisible"])
            assertEquals("SAVE100", capsule.events.last { it.type == "text" }.data["value"])
            assertTrue(capsule.events.any { it.type == "route" && it.data["location"] == "/id/cart?source=home" })
            val cart = capsule.events.last { it.type == "http" && it.data["method"] == "GET" }
            assertEquals("GET api.shop.example/api/cart", cart.data["key"])
            assertEquals("[REDACTED]", (cart.data["requestHeaders"] as Map<*, *>)["authorization"])
            assertEquals("[REDACTED]", (cart.data["responseHeaders"] as Map<*, *>)["set-cookie"])
            // Pointer coordinates are dp within the screen.
            val down = capsule.events.first { it.type == "pointer" && it.data["phase"] == "down" }
            val x = (down.data["x"] as Number).toDouble()
            assertTrue(x > 0 && x < m.platform.screenWidth)
            assertTrue(m.capabilities["network.okhttp"] == "supported")
        }
        assertTrue("monitoring envelopes flush before test uninstall", BugCapsule.flush(15_000))
    }

    private fun awaitNewCapture(before: Set<String>): File {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            BugCapsule.savedCaptures().firstOrNull { it.name !in before }?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("no capsule written")
    }
}
