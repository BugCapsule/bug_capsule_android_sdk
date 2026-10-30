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
import java.time.Instant

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
        if (patch.available) patch.markHealthy("first_screen")
        val record = JSONObject()
            .put("at", Instant.now().toString())
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
