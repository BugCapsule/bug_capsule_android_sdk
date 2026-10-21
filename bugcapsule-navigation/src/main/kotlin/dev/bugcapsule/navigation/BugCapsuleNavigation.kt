package dev.bugcapsule.navigation

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleIntegration
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.DartUri
import dev.bugcapsule.android.core.EventTypes
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.core.Redactor
import java.lang.ref.WeakReference

/** Rewrites a location after built-in redaction (e.g. secrets in path segments). */
fun interface LocationSanitizer {
    fun sanitize(location: String): String
}

/**
 * Jetpack Navigation integration (`navigation.jetpack`): records `route`
 * events (`source: "navigation"`), snapshots the current destination and back
 * stack in periodic checkpoints (`navigation`: `{route, backStack}`) and
 * restores them in replay.
 *
 * ```kotlin
 * val navigation = BugCapsuleNavigation()
 * BugCapsule.init(app, config, listOf(navigation))
 * // in the activity, after the graph is set:
 * navigation.attach(navHostFragment.navController)
 * ```
 *
 * Monitoring: each destination change starts a `navigation` transaction
 * named after the destination's route pattern (`/product/{id}`, never the
 * arguments) with its initial display, and route events become navigation
 * breadcrumbs. Pass `traceScreens = false` to keep only the breadcrumbs.
 *
 * Works with Fragment and Compose navigation (any [NavController]). Routes
 * are redacted like URLs (sensitive query values); argument values that are
 * not strings or numbers (Parcelables) are not restored.
 */
class BugCapsuleNavigation @JvmOverloads constructor(
    private val sanitizeLocation: LocationSanitizer? = null,
    private val traceScreens: Boolean = true,
) : BugCapsuleIntegration, NavController.OnDestinationChangedListener {
    override val name: String = NAME

    @Volatile
    private var session: BugCapsuleSession? = null
    private var controller: WeakReference<NavController>? = null
    private var pending: Map<String, Any?>? = null
    private var lastLocation: String? = null

    override fun setUp(session: BugCapsuleSession) {
        this.session = session
        session.applicationContext
    }

    override fun capabilities() = mapOf("navigation.jetpack" to "supported")

    /** Starts recording [navController] (call on the main thread after its graph is set). */
    fun attach(navController: NavController) {
        controller?.get()?.removeOnDestinationChangedListener(this)
        controller = WeakReference(navController)
        val restore = pending
        if (restore != null) {
            pending = null
            applyRestore(navController, restore)
        }
        navController.addOnDestinationChangedListener(this)
    }

    fun detach(navController: NavController) {
        navController.removeOnDestinationChangedListener(this)
        if (controller?.get() === navController) controller = null
    }

    override fun onDestinationChanged(controller: NavController, destination: NavDestination, arguments: Bundle?) {
        val s = session ?: return
        if (!s.isObserving) return
        try {
            val location = location(s, destination, arguments)
            if (location == lastLocation) return
            if (traceScreens && !s.isReplaying) BugCapsule.startScreen("/" + name(destination), "navigation")
            val action = if (lastLocation == null) "reset" else "push"
            lastLocation = location
            s.record(EventTypes.ROUTE, linkedMapOf("location" to location, "source" to "navigation", "action" to action))
        } catch (_: Exception) {
        }
    }

    private fun name(destination: NavDestination): String {
        destination.route?.let { return it }
        return try {
            "id/" + session!!.applicationContext.resources.getResourceEntryName(destination.id)
        } catch (_: Exception) {
            "id/${destination.id}"
        }
    }

    private fun args(destination: NavDestination, arguments: Bundle?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (arguments == null) return out
        for (key in arguments.keySet().sorted()) {
            if (key.startsWith("android-support-nav:")) continue
            @Suppress("DEPRECATION")
            val v = arguments.get(key)
            if (v is String || v is Number || v is Boolean) out[key] = v.toString()
        }
        return out
    }

    /** `/<route or id/name>?arg=value`, redacted like a URL. */
    private fun location(s: BugCapsuleSession, destination: NavDestination, arguments: Bundle?): String {
        val sb = StringBuilder("/").append(name(destination))
        val args = args(destination, arguments)
        if (args.isNotEmpty()) {
            sb.append('?').append(args.entries.joinToString("&") { "${DartUri.encodeQueryComponent(it.key)}=${DartUri.encodeQueryComponent(it.value)}" })
        }
        val redacted = s.redactor.redactUrl(sb.toString())
        return sanitizeLocation?.sanitize(redacted) ?: redacted
    }

    private fun entryJson(s: BugCapsuleSession, entry: NavBackStackEntry): Map<String, Any?> {
        val args = args(entry.destination, entry.arguments)
        val redactedArgs = LinkedHashMap<String, Any?>()
        for ((k, v) in args) redactedArgs[k] = if (s.redactor.isSensitiveQueryKey(k)) Redactor.PLACEHOLDER else s.redactor.scrubText(v)
        return linkedMapOf(
            "location" to location(s, entry.destination, entry.arguments),
            "destination" to name(entry.destination),
            "args" to redactedArgs,
        )
    }

    @SuppressLint("RestrictedApi")
    override fun snapshot(kind: CheckpointKind): Any? {
        if (kind != CheckpointKind.PERIODIC) return null
        val s = session ?: return null
        val nav = controller?.get() ?: return null
        val current = nav.currentBackStackEntry ?: return null
        val stack = try {
            nav.currentBackStack.value.filter { it.destination !is NavGraph }.map { entryJson(s, it) }
        } catch (_: Throwable) {
            listOf(entryJson(s, current))
        }
        return linkedMapOf("route" to location(s, current.destination, current.arguments), "backStack" to stack)
    }

    override fun restore(data: Any?, session: BugCapsuleSession) {
        val map = J.obj(data) ?: return
        val nav = controller?.get()
        if (nav != null) applyRestore(nav, map) else pending = map
    }

    private fun applyRestore(nav: NavController, data: Map<String, Any?>) {
        val replay = session?.replay
        if (replay?.inputsStarted == true) replay.diverge("restore.late", "navigation attached after the first replayed input")
        val route = J.str(data["route"]) ?: ""
        if (route.contains(Redactor.PLACEHOLDER) || route.contains(DartUri.encodeQueryComponent(Redactor.PLACEHOLDER))) {
            replay?.diverge("route.redacted", "restored route has redacted parameters")
        }
        val stack = (J.list(data["backStack"]) ?: emptyList()).mapNotNull { J.obj(it) }
        val start = nav.currentDestination
        for ((i, entry) in stack.withIndex()) {
            val destinationName = J.str(entry["destination"]) ?: continue
            if (i == 0 && start != null && name(start) == destinationName) continue
            try {
                navigate(nav, destinationName, J.obj(entry["args"]) ?: emptyMap())
            } catch (e: Exception) {
                replay?.diverge("restore.navigation", "$destinationName: $e")
                return
            }
        }
    }

    private fun navigate(nav: NavController, destinationName: String, args: Map<String, Any?>) {
        val graph = nav.graph
        val target: NavDestination = if (destinationName.startsWith("id/")) {
            val entry = destinationName.removePrefix("id/")
            val ctx = session!!.applicationContext
            val id = entry.toIntOrNull() ?: ctx.resources.getIdentifier(entry, "id", ctx.packageName)
            graph.findNode(id) ?: throw IllegalStateException("destination $destinationName is not in the graph")
        } else {
            graph.findNode(destinationName) ?: throw IllegalStateException("route $destinationName is not in the graph")
        }
        val bundle = Bundle()
        for ((key, raw) in args) {
            val value = raw?.toString() ?: continue
            if (value.contains(Redactor.PLACEHOLDER)) session?.replay?.diverge("route.redacted", "argument $key")
            val arg = target.arguments[key]
            if (arg != null) {
                @Suppress("UNCHECKED_CAST")
                val type = arg.type as androidx.navigation.NavType<Any?>
                type.put(bundle, key, type.parseValue(value))
            } else {
                bundle.putString(key, value)
            }
        }
        nav.navigate(target.id, bundle)
    }

    companion object {
        const val NAME = "navigation"
    }
}
