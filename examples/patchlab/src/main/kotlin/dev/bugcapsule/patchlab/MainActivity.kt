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
import dev.bugcapsule.android.core.Iso8601

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
        val patch = BugCapsulePatch.open(
            PatchConfig(
                storeDir = File(filesDir, "bcpatch").path,
                projectId = remote?.getString("projectId") ?: "demo",
                applicationId = packageName,
                environment = "staging",
                distributionProfile = "development",
                baseBuildId = BuildConfig.BASE_BUILD_ID,
                installationId = "device-1",
                trustRoots = listOf(remote?.getString("trustRoot") ?: BuildConfig.TRUST_ANCHOR),
            ),
            listOf(coupon),
            listOf(summarySurface),
        )
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

    /** Applies whatever the delivery harness dropped. Takes effect next launch. */
    private fun processInbox(patch: BugCapsulePatch, exchange: File): JSONArray {
        val log = JSONArray()
        val files = File(exchange, "inbox").listFiles()?.sortedBy { it.name } ?: return log
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
