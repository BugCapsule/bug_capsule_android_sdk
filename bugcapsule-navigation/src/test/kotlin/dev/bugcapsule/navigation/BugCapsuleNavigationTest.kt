package dev.bugcapsule.navigation

import android.content.Context
import androidx.navigation.NavType
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.NavDestinationBuilder
import androidx.navigation.NavDestination
import androidx.navigation.Navigator
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.BugCapsuleSession
import dev.bugcapsule.android.ReplayContext
import dev.bugcapsule.android.WorkToken
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.CheckpointKind
import dev.bugcapsule.android.core.Divergence
import dev.bugcapsule.android.core.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@Navigator.Name("screen")
class ScreenNavigator : Navigator<NavDestination>() {
    override fun createDestination() = NavDestination(this)
}

@RunWith(RobolectricTestRunner::class)
class BugCapsuleNavigationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Session(override val applicationContext: Context, override var replay: ReplayContext? = null) : BugCapsuleSession {
        val events = ArrayList<Pair<String, Map<String, Any?>>>()
        override val config = BugCapsuleConfig.builder().build()
        override val mode = CaptureMode.QA
        override val isRecording get() = replay == null
        override val isReplaying get() = replay != null
        override val redactor = Redactor()
        override val captureNetworkBodies = true
        override val maxBodyBytes = 1000
        override fun now() = 0L
        override fun record(type: String, data: Map<String, Any?>) { events.add(type to data) }
        override fun reportError(error: Throwable) {}
        override fun schedule(delayMs: Long, task: Runnable) {}
    }

    private fun controller() = TestNavHostController(context).apply {
        val navigator = ScreenNavigator()
        navigatorProvider.addNavigator(navigator)
        graph = createGraph(startDestination = "home") {
            destination(NavDestinationBuilder(navigator, "home"))
            destination(NavDestinationBuilder(navigator, "product/{sku}").apply { argument("sku") { type = NavType.StringType } })
            destination(
                NavDestinationBuilder(navigator, "cart?token={token}").apply {
                    argument("token") { type = NavType.StringType; nullable = true; defaultValue = null }
                },
            )
        }
    }

    @Test
    fun recordsRedactedRouteEvents() {
        val session = Session(context)
        val nav = BugCapsuleNavigation().also { it.setUp(session) }
        val c = controller()
        nav.attach(c)
        c.navigate("product/mug")
        c.navigate("cart?token=secret-abc")
        val locations = session.events.filter { it.first == "route" }.map { it.second["location"] }
        assertEquals("/home", locations[0])
        assertEquals("/product/{sku}?sku=mug", locations[1])
        assertTrue(locations[2].toString().contains("token=%5BREDACTED%5D"))
        assertEquals("navigation", session.events[0].second["source"])
    }

    @Test
    fun periodicSnapshotHoldsRouteAndBackStack() {
        val session = Session(context)
        val nav = BugCapsuleNavigation().also { it.setUp(session) }
        val c = controller()
        nav.attach(c)
        c.navigate("product/mug")
        assertNull(nav.snapshot(CheckpointKind.LAUNCH))
        val snap = nav.snapshot(CheckpointKind.PERIODIC) as Map<*, *>
        assertEquals("/product/{sku}?sku=mug", snap["route"])
        val stack = snap["backStack"] as List<*>
        assertEquals(listOf("home", "product/{sku}"), stack.map { (it as Map<*, *>)["destination"] })
    }

    @Test
    fun restoresTheBackStackWhenAttached() {
        val divergences = ArrayList<Divergence>()
        val replay = object : ReplayContext {
            override val capsule get() = throw UnsupportedOperationException()
            override val settings get() = throw UnsupportedOperationException()
            override val fixtures get() = throw UnsupportedOperationException()
            override fun diverge(type: String, detail: String) { divergences.add(Divergence(type, detail)) }
            override fun fixtureServed() {}
            override fun beginWork() = WorkToken {}
            override fun activity() {}
            override val inputsStarted = false
        }
        val session = Session(context, replay)
        val nav = BugCapsuleNavigation().also { it.setUp(session) }
        nav.restore(
            mapOf(
                "route" to "/product/{sku}?sku=tee",
                "backStack" to listOf(
                    mapOf("destination" to "home", "args" to emptyMap<String, Any?>()),
                    mapOf("destination" to "product/{sku}", "args" to mapOf("sku" to "tee")),
                ),
            ),
            session,
        )
        val c = controller()
        nav.attach(c)
        assertEquals("product/{sku}", c.currentDestination?.route)
        assertEquals("tee", c.currentBackStackEntry?.arguments?.getString("sku"))
        assertTrue(divergences.isEmpty())
    }
}
