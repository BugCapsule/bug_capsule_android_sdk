package dev.bugcapsule.patchlab

import android.os.Bundle
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bugcapsule.patch.BugCapsulePatch
import dev.bugcapsule.patch.HttpPatchTransport
import dev.bugcapsule.patch.PatchConfig
import dev.bugcapsule.patch.PatchModule
import dev.bugcapsule.patch.PatchSurfaceContract
import dev.bugcapsule.patch.PatchUpdater
import dev.bugcapsule.patch.compose.PatchSurfaceView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.Iso8601
import dev.bugcapsule.patch.BugCapsulePatchBinding
import androidx.lifecycle.Lifecycle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class Line(val sku: String, val qty: Int, val unitCents: Long)
data class Cart(val lines: List<Line>, val couponPercent: Int)
data class Priced(val subtotal: String, val discount: String, val total: String, val label: String)

private fun money(cents: Long): String {
    val abs = Math.abs(cents)
    return "${if (cents < 0) "-" else ""}${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

/** The shipped behavior, with the bug the patch fixes: the percentage is applied without dividing by 100. */
private fun bundledCoupon(c: Cart): Priced {
    val subtotal = c.lines.sumOf { it.unitCents * it.qty }
    val discount = subtotal * c.couponPercent
    return Priced(money(subtotal), money(discount), money(subtotal - discount), "BASE ${c.couponPercent}%")
}

private val coupon = PatchModule<Cart, Priced>(
    id = "pricing.coupon",
    inputSchema = "rec(couponPercent:int,lines:list(rec(qty:int,sku:str,unitPrice:dec(2))))",
    outputSchema = "rec(discount:dec(2),label:str,subtotal:dec(2),total:dec(2))",
    encode = { c ->
        JSONObject().put("couponPercent", c.couponPercent).put(
            "lines",
            JSONArray(c.lines.map { JSONObject().put("sku", it.sku).put("qty", it.qty).put("unitPrice", money(it.unitCents)) }),
        )
    },
    decode = { o ->
        val m = o as JSONObject
        Priced(m.getString("subtotal"), m.getString("discount"), m.getString("total"), m.getString("label"))
    },
    fallback = ::bundledCoupon,
)

/**
 * The app's own smoke check on the price it is about to show. It recomputes the
 * expected subtotal from the cart (the product rule: lines of 10+ units get 5%
 * off, half-even) and checks the discount and total against it, independently
 * of the patch's code. Returns why the result is not acceptable, or null. A
 * patched result that fails is never marked healthy, so a signed but wrong
 * patch stays in probation and the engine rolls it back.
 *
 * Amounts are parsed with a bound (at most 9 whole digits) before any
 * arithmetic, so a huge value is refused instead of overflowing.
 */
internal fun smokeFailure(c: Cart, p: Priced): String? {
    val s = boundedCents(p.subtotal) ?: return "unparseable_amounts"
    val d = boundedCents(p.discount) ?: return "unparseable_amounts"
    val t = boundedCents(p.total) ?: return "unparseable_amounts"
    if (s != expectedSubtotalCents(c)) return "subtotal_mismatch"
    if (d != halfEven(s * c.couponPercent.coerceIn(0, 50), 100)) return "discount_mismatch"
    if (t != s - d) return "total_mismatch"
    if (p.label.isEmpty()) return "empty_label"
    return null
}

/** Lab carts are small (quantities and prices well under 10^6), so this cannot overflow. */
internal fun expectedSubtotalCents(c: Cart): Long = c.lines.sumOf {
    val gross = it.unitCents * it.qty
    if (it.qty >= 10) halfEven(gross * 95, 100) else gross
}

private val AMOUNT = Regex("-?(0|[1-9][0-9]{0,8})\\.[0-9]{2}")

internal fun boundedCents(text: String): Long? {
    if (!AMOUNT.matches(text)) return null
    val negative = text.startsWith("-")
    val (whole, frac) = text.removePrefix("-").split('.')
    val v = whole.toLong() * 100 + frac.toLong()
    return if (negative) -v else v
}

private fun halfEven(n: Long, d: Long): Long {
    val q = n / d
    val r = n % d
    return if (r * 2 > d || (r * 2 == d && q % 2 != 0L)) q + 1 else q
}

private val sample = Cart(listOf(Line("a", 2, 1999), Line("b", 10, 300)), 20)

private val summarySurface = PatchSurfaceContract(
    id = "lab.summary",
    components = listOf("column", "row", "text", "spacer", "button"),
    actions = listOf("lab.pay"),
    requiredActions = listOf("lab.pay"),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val exchange = getExternalFilesDir(null)!!
        // Test-only: an acceptance run points the lab at a real backend. Without
        // this file the lab uses its file inbox. The base build id stays the binary's.
        val remoteFile = File(exchange, "patch-config.json")
        val remote = if (remoteFile.exists()) JSONObject(remoteFile.readText()) else null
        val patchConfig = PatchConfig(
            storeDir = File(filesDir, "bcpatch").path,
            projectId = remote?.getString("projectId") ?: "demo",
            applicationId = packageName,
            environment = "staging",
            distributionProfile = "development",
            baseBuildId = BuildConfig.BASE_BUILD_ID,
            installationId = "device-1",
            trustRoots = listOf(remote?.getString("trustRoot") ?: BuildConfig.TRUST_ANCHOR),
        )
        // Test-only, opt-in: a local acceptance run turns on BugCapsule monitoring so
        // the SDK's own release-health sessions carry the running patch identity.
        // The binding then owns the one runtime of this process.
        val monitoringFile = File(exchange, "monitoring-config.json")
        val monitoring = if (monitoringFile.exists()) JSONObject(monitoringFile.readText()) else null
        val binding = monitoring?.let { BugCapsulePatchBinding(patchConfig, listOf(coupon), listOf(summarySurface)) }
        if (monitoring != null) {
            BugCapsule.init(
                application,
                BugCapsuleConfig.Builder()
                    .backend(monitoring.getString("backendUrl"), monitoring.getString("ingestKey"))
                    .release(monitoring.optString("release", "dev.bugcapsule.patchlab@1.0+1"))
                    .environment("staging")
                    .mode(CaptureMode.QA)
                    .gitSha("patchlab")
                    .autoCaptureOnFailure(false)
                    .patchBinding(binding)
                    .build(),
            )
        }
        val patch = binding?.patch ?: BugCapsulePatch.open(patchConfig, listOf(coupon), listOf(summarySurface))
        val start = System.nanoTime()
        val result = patch.call(coupon, sample)
        val micros = (System.nanoTime() - start) / 1000
        val before = if (patch.available) patch.status() else JSONObject().put("state", "UNAVAILABLE")
        val inbox = if (patch.available) processInbox(patch, exchange) else JSONArray()
        // Only a patched result that passes the app's own smoke check is healthy.
        val health = JSONObject().put("checked", false)
        if (patch.available && result.source.name.lowercase() == "patched") {
            health.put("checked", true)
            val why = smokeFailure(sample, result.value)
            if (why != null) {
                health.put("ok", false).put("reason", why)
            } else {
                health.put("ok", true)
                try {
                    health.put("markHealthy", patch.markHealthy("first_screen"))
                } catch (e: Exception) {
                    health.put("markHealthyError", e.toString())
                }
            }
        }
        val record = JSONObject()
            .put("at", Iso8601.format(System.currentTimeMillis()))
            .put("mode", if (BuildConfig.DEBUG || Debug.isDebuggerConnected()) "debug" else "release")
            .put("platform", "android")
            .put("baseBuildId", BuildConfig.BASE_BUILD_ID)
            .put("source", result.source.name.lowercase())
            .put("reason", result.reason ?: JSONObject.NULL)
            .put("output", JSONObject().put("subtotal", result.value.subtotal).put("discount", result.value.discount).put("total", result.value.total).put("label", result.value.label))
            .put("evalMicros", micros)
            .put("state", before.getString("state"))
            .put("running", before.opt("running"))
            .put("inbox", inbox)
            .put("health", health)
            .put("monitoring", if (binding == null) "off" else "on")
            .put("status", if (patch.available) patch.status() else JSONObject.NULL)
        val results = File(exchange, "results.jsonl")
        if (remote == null) {
            results.appendText(record.toString() + "\n")
        } else {
            // Blocking network: off the main thread, as the updater requires.
            Thread {
                val updater = PatchUpdater(
                    patch,
                    HttpPatchTransport(remote.getString("baseUrl"), remote.getString("deliveryToken"), packageName, "android", "development", BuildConfig.BASE_BUILD_ID, "device-1"),
                )
                val r = updater.checkForUpdate()
                val sent = try {
                    updater.flushReceipts()
                } catch (e: java.io.IOException) {
                    record.put("receiptError", e.toString())
                    -1
                }
                record.put("update", JSONObject().put("state", r.state.name).put("artifactDigest", r.artifactDigest ?: JSONObject.NULL).put("reason", r.reason ?: JSONObject.NULL).put("receiptsSent", sent))
                record.put("status", patch.status())
                if (binding != null) {
                    // Telemetry runs end the release-health session in order and send
                    // it before the result is recorded: the harness then stops the
                    // process, which must not read as an abnormal session. Wait until
                    // the activity is resumed (so no later foreground opens another
                    // session), end and send it, then close monitoring.
                    val resumed = awaitResumed()
                    record.put("resumed", resumed)
                    record.put("uncaughtHandler", Thread.getDefaultUncaughtExceptionHandler()?.javaClass?.name ?: JSONObject.NULL)
                    // Acceptance only, telemetry mode only: a one-shot genuine crash (an
                    // uncaught exception on the main thread) while this session is open.
                    // The flag is removed first, so the next launch runs normally and the
                    // SDK's crash handler is what reports it.
                    val crashFlag = File(exchange, "crash-once")
                    if (!resumed) {
                        // Fail visibly: the harness rejects a record without a resumed activity.
                        record.put("lifecycleError", "activity not resumed within 5 s; session left open")
                    } else if (crashFlag.exists()) {
                        check(crashFlag.delete()) { "the one-shot crash flag could not be removed" }
                        runOnUiThread { throw IllegalStateException("PatchLab acceptance: deliberate one-shot crash") }
                        return@Thread
                    } else {
                        BugCapsule.endSession()
                        record.put("sessionFlushed", BugCapsule.flush(10_000))
                        BugCapsule.close(5_000)
                    }
                }
                results.appendText(record.toString() + "\n")
            }.start()
        }
        val state = before.getString("state")
        setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                        Text("Patch Lab · Android", style = MaterialTheme.typography.titleMedium)
                        Text("Source: ${result.source}")
                        Text("State: $state")
                        Text("Label: ${result.value.label}")
                        Text("Total: ${result.value.total}")
                        Text("")
                        PatchSurfaceView(patch, summarySurface.id, mapOf("lab.pay" to {})) {
                            Column {
                                Text("Bundled summary")
                                Button(onClick = {}) { Text("Pay") }
                            }
                        }
                    }
                }
            }
        }
    }

    /** True once the activity is resumed; false after about 5 s. */
    private fun awaitResumed(): Boolean {
        repeat(100) {
            val resumed = CountDownLatch(1)
            var state = Lifecycle.State.INITIALIZED
            runOnUiThread {
                state = lifecycle.currentState
                resumed.countDown()
            }
            resumed.await(1, TimeUnit.SECONDS)
            if (state.isAtLeast(Lifecycle.State.RESUMED)) return true
            Thread.sleep(50)
        }
        return false
    }

    /** Applies whatever the delivery harness dropped. Takes effect next launch. */
    private fun processInbox(patch: BugCapsulePatch, exchange: File): JSONArray {
        val log = JSONArray()
        // The app owns its inbox: on Android 11+ a directory created by adb is not
        // listable by the app, while files pushed into an app-owned one are.
        val inbox = File(exchange, "inbox").apply { mkdirs() }
        val files = inbox.listFiles()?.sortedBy { it.name } ?: return log
        for (f in files) {
            try {
                if (f.name.endsWith(".channel")) {
                    val sig = File(f.path + ".sig")
                    log.put("channel ${patch.applyChannel(f.readBytes(), sig.readBytes())}")
                    sig.delete()
                    f.delete()
                } else if (f.name.endsWith(".bcpatch")) {
                    log.put("stage ${patch.stage(f.readBytes())}")
                    f.delete()
                }
            } catch (e: Exception) {
                log.put("rejected ${f.name}: $e")
                f.delete()
            }
        }
        return log
    }
}
