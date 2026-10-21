package dev.bugcapsule.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.feedback.FeedbackTexts

/**
 * Marks a Compose screen for monitoring: starts a `ui.load` transaction
 * named [name] (no ids in it: `ProductDetail`, not `Product 42`) when the
 * screen enters the composition, with its initial display at the next frame.
 * Not needed for destinations of [BugCapsuleNavigationEffect] (those are
 * `navigation` transactions already).
 *
 * ```kotlin
 * @Composable fun CheckoutScreen() {
 *     BugCapsuleScreen("Checkout")
 *     ...
 * }
 * ```
 */
@Composable
fun BugCapsuleScreen(name: String) {
    val view = LocalView.current
    SideEffect { BugCapsule.markFramework("compose") }
    DisposableEffect(name) {
        BugCapsule.startScreen(name, "ui.load", view)
        onDispose { }
    }
}

/**
 * Reports the screen fully drawn (TTFD, `ui.load.full_display`) the first
 * time [ready] is true, e.g. when its data has loaded. Needs
 * `enableTimeToFullDisplayTracing(true)`.
 */
@Composable
fun BugCapsuleFullyDrawn(ready: Boolean) {
    var reported by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (ready && !reported) {
            reported = true
            BugCapsule.reportFullyDrawn()
        }
    }
}

/**
 * The ready-made feedback form for Compose (monitoring-v1 §9) as a Material 3
 * bottom sheet: message, optional name and email. "Send" calls
 * [BugCapsule.captureFeedback] and then [onDismiss].
 *
 * ```kotlin
 * var show by remember { mutableStateOf(false) }
 * if (show) BugCapsuleFeedbackSheet(onDismiss = { show = false })
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugCapsuleFeedbackSheet(
    onDismiss: () -> Unit,
    eventId: String? = null,
    texts: FeedbackTexts = FeedbackTexts(),
    initialName: String = "",
    initialEmail: String = "",
    onSubmitted: (feedbackId: String?) -> Unit = {},
) {
    var message by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var showError by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(texts.title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = message,
                onValueChange = {
                    message = it
                    showError = false
                },
                label = { Text(texts.messageHint) },
                isError = showError,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(texts.nameHint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text(texts.emailHint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text(texts.cancel) }
                Button(onClick = {
                    val text = message.trim()
                    if (text.isEmpty()) {
                        showError = true
                        return@Button
                    }
                    val id = BugCapsule.captureFeedback(
                        text,
                        name.trim().takeIf { it.isNotEmpty() },
                        email.trim().takeIf { it.isNotEmpty() },
                        eventId,
                    )
                    onSubmitted(id)
                    onDismiss()
                }) { Text(texts.submit) }
            }
        }
    }
}
