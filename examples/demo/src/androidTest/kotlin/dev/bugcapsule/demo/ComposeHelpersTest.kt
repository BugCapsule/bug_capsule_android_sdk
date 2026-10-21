package dev.bugcapsule.demo

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.core.CapsuleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Compose helpers on a real device: tracked text, sensitive fields, semantic actions, OkHttp capture. */
@RunWith(AndroidJUnit4::class)
class ComposeHelpersTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun trackedTextSensitivePasswordAndSemanticAction() {
        assertTrue("BugCapsule records in the instrumented app", BugCapsule.isRecording)
        compose.setContent { AccountScreen() }
        compose.onNodeWithTag("email").performTextInput("qa@example.com")
        compose.onNodeWithTag("password").performTextInput("hunter2")
        compose.onNodeWithTag("signIn").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTagText("status", "Signed in as Sam")
        }
        compose.onNodeWithTag("status").assertTextEquals("Signed in as Sam")

        val capture = BugCapsule.capture("compose test").get(10, TimeUnit.SECONDS)!!
        val capsule = CapsuleCodec.decode(capture.file.readBytes())
        val texts = capsule.events.filter { it.type == "text" }
        assertEquals("qa@example.com", texts.last { it.data["id"] == "account.email" }.data["value"])
        assertTrue(texts.filter { it.data["id"] == "account.password" }.all { it.data["value"] == "[REDACTED]" })
        assertTrue(capsule.events.any { it.type == "action" && it.data["id"] == "account.signIn" })
        val login = capsule.events.first { it.type == "http" && (it.data["key"] as String).startsWith("POST api.shop.example/api/login") }
        assertEquals("[REDACTED]", (login.data["requestBody"] as Map<*, *>)["password"])
        assertEquals("[REDACTED]", (login.data["responseBody"] as Map<*, *>)["token"])
        assertEquals("[REDACTED]", (login.data["requestHeaders"] as Map<*, *>)["authorization"])
        assertTrue(capsule.manifest.runtime!!.frameworks.contains("compose"))
        assertEquals("manual capture is ineligible", "ineligible", capsule.manifest.eligibility.status.wire)

        // Semantic replay invokes the registered handler by id.
        compose.runOnIdle { assertTrue(BugCapsule.invokeAction("account.signIn")) }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagText(tag: String, text: String): Boolean =
        try {
            onNodeWithTag(tag).assertTextEquals(text)
            true
        } catch (_: AssertionError) {
            false
        }
}
