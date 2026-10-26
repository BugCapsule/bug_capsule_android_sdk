package dev.bugcapsule.android.replay.example

import dev.bugcapsule.android.adapters.NativeAdapter
import dev.bugcapsule.android.adapters.NativeCallback
import dev.bugcapsule.android.adapters.NativeError
import dev.bugcapsule.android.adapters.NativeInteractions
import dev.bugcapsule.android.adapters.NativeSubscriber
import dev.bugcapsule.android.adapters.NativeSubscription

// A minimal native adapter, as an app team would write one: the app talks to
// a location SDK through its own `LocationProvider` interface (no Google
// Play Services here), and `BugCapsuleLocationProvider` wraps the real
// provider so BugCapsule records it and replays it offline.

data class Location(val latitude: Double, val longitude: Double, val accuracyM: Float, val timeMs: Long)

interface LocationCallback {
    fun onLocation(location: Location?)
    fun onError(code: String, message: String?)
}

fun interface LocationListener {
    fun onLocation(location: Location)
}

fun interface Cancellable {
    fun cancel()
}

/** The app's abstraction over the location SDK. */
interface LocationProvider {
    fun lastLocation(callback: LocationCallback)
    fun requestUpdates(intervalMs: Long, listener: LocationListener): Cancellable
}

/** Identity, operations and payload policy of the adapter. */
class LocationAdapter : NativeAdapter {
    override val id = "example.location"
    override val version = "1.0.0"
    override val schema = 1
    override val operations = setOf(LAST, UPDATES)
    override val tested = mapOf("example-location-sdk" to "2.1.0")

    companion object {
        const val LAST = "location.last"
        const val UPDATES = "location.updates"

        /** SDK object -> typed value (what is recorded). */
        fun encode(l: Location?): Map<String, Any?>? = l?.let {
            linkedMapOf("lat" to it.latitude, "lng" to it.longitude, "accuracy" to it.accuracyM, "time" to it.timeMs)
        }

        /** Typed value -> SDK object (what the app gets, in capture and replay alike). */
        fun decode(value: Any?): Location? {
            val m = value as? Map<*, *> ?: return null
            return Location(
                (m["lat"] as Number).toDouble(),
                (m["lng"] as Number).toDouble(),
                (m["accuracy"] as Number).toFloat(),
                (m["time"] as Number).toLong(),
            )
        }
    }
}

/** The wrapper the app uses instead of the real provider. */
class BugCapsuleLocationProvider(
    private val real: LocationProvider,
    private val bridge: NativeInteractions,
) : LocationProvider {
    override fun lastLocation(callback: LocationCallback) {
        bridge.call(
            LocationAdapter.LAST,
            null,
            object : NativeCallback {
                override fun onResult(value: Any?) = callback.onLocation(LocationAdapter.decode(value))
                override fun onError(error: NativeError) = callback.onError(error.code, error.message)
            },
        ) { cb ->
            real.lastLocation(object : LocationCallback {
                override fun onLocation(location: Location?) = cb.onResult(LocationAdapter.encode(location))
                override fun onError(code: String, message: String?) = cb.onError(NativeError(code, message))
            })
        }
    }

    override fun requestUpdates(intervalMs: Long, listener: LocationListener): Cancellable {
        val subscription = bridge.subscribe(
            LocationAdapter.UPDATES,
            mapOf("intervalMs" to intervalMs),
            object : NativeSubscriber {
                override fun onEvent(value: Any?) {
                    LocationAdapter.decode(value)?.let { listener.onLocation(it) }
                }

                override fun onError(error: NativeError) {}
            },
        ) { subscriber ->
            val handle = real.requestUpdates(intervalMs) { subscriber.onEvent(LocationAdapter.encode(it)) }
            NativeSubscription { handle.cancel() }
        }
        return Cancellable { subscription.cancel() }
    }
}
