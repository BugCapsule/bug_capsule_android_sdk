package dev.bugcapsule.patchlab

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.widget.TextView
import dev.bugcapsule.patch.BugCapsulePatch
import dev.bugcapsule.patch.PatchConfig
import dev.bugcapsule.patch.PatchModule
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

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val exchange = getExternalFilesDir(null)!!
        val patch = BugCapsulePatch.open(
            PatchConfig(
                storeDir = File(filesDir, "bcpatch").path,
                projectId = "demo",
                applicationId = packageName,
                environment = "staging",
                distributionProfile = "development",
                baseBuildId = BuildConfig.BASE_BUILD_ID,
                installationId = "device-1",
                trustRoots = listOf(BuildConfig.TRUST_ANCHOR),
            ),
            listOf(coupon),
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
        File(exchange, "results.jsonl").appendText(record.toString() + "\n")
        setContentView(TextView(this).apply { text = "Source: ${result.source}\nState: ${before.getString("state")}\nLabel: ${result.value.label}\nTotal: ${result.value.total}" })
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
