package dev.bugcapsule.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import dev.bugcapsule.android.ActionHandler
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.navigation.BugCapsuleNavigation

/**
 * A click handler registered as the semantic action [id]: it records the
 * action and runs [onClick]; semantic replay invokes [onClick] by id while
 * the composable is in the composition.
 *
 * ```kotlin
 * Button(onClick = rememberBugCapsuleAction("cart.checkout") { viewModel.checkout() }) { Text("Checkout") }
 * ```
 */
@Composable
fun rememberBugCapsuleAction(id: String, onClick: () -> Unit): () -> Unit {
    val current by rememberUpdatedState(onClick)
    SideEffect { BugCapsule.markFramework("compose") }
    DisposableEffect(id) {
        val handler = ActionHandler { current() }
        BugCapsule.registerAction(id, handler)
        onDispose { BugCapsule.unregisterAction(id, handler) }
    }
    return remember(id) {
        {
            BugCapsule.recordAction(id)
            current()
        }
    }
}

/** Text state tracked under an id: bind [value] and [onValueChange] to a `TextField`. */
@Stable
class TrackedText internal constructor(initial: String) {
    var value: String by mutableStateOf(initial)
        internal set
    internal var tracker: BugCapsule.TextTracker? = null

    fun onValueChange(newValue: String) {
        value = newValue
        tracker?.onChanged(newValue)
    }
}

/**
 * Remembers a [TrackedText] whose edits are recorded under [id] and restored
 * in replay. Pass `sensitive = true` for password fields
 * (`PasswordVisualTransformation`): their values are never recorded.
 *
 * ```kotlin
 * val coupon = rememberTrackedText("cart.coupon")
 * TextField(value = coupon.value, onValueChange = coupon::onValueChange)
 * ```
 */
@Composable
fun rememberTrackedText(id: String, initial: String = "", sensitive: Boolean = false): TrackedText {
    val state = remember(id) { TrackedText(initial) }
    SideEffect { BugCapsule.markFramework("compose") }
    DisposableEffect(id, sensitive) {
        val tracker = BugCapsule.trackText(id, { v -> state.value = v }, sensitive)
        state.tracker = tracker
        onDispose {
            tracker.untrack()
            state.tracker = null
        }
    }
    return state
}

/** Attaches [navigation] to [navController] while in the composition (Compose Navigation). */
@Composable
fun BugCapsuleNavigationEffect(navController: NavController, navigation: BugCapsuleNavigation) {
    DisposableEffect(navController, navigation) {
        BugCapsule.markFramework("compose")
        navigation.attach(navController)
        onDispose { navigation.detach(navController) }
    }
}
