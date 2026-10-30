package dev.bugcapsule.capsulelab

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.patch.BugCapsulePatch
import dev.bugcapsule.patch.PatchModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Line(val sku: String, val qty: Int, val unitCents: Long)
data class Cart(val lines: List<Line>, val couponPercent: Int)
data class Priced(val subtotal: String, val discount: String, val total: String, val label: String)

private fun money(cents: Long): String {
    val abs = Math.abs(cents)
    return "${if (cents < 0) "-" else ""}${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

/** The shipped behavior, with the bug the fix removes: the percentage is applied without dividing by 100. */
private fun bundledCoupon(c: Cart): Priced {
    val subtotal = c.lines.sumOf { it.unitCents * it.qty }
    val discount = subtotal * c.couponPercent
    return Priced(money(subtotal), money(discount), money(subtotal - discount), "BASE ${c.couponPercent}%")
}

val coupon = PatchModule<Cart, Priced>(
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

/**
 * Captures a real failure on whatever patch is active, for replay acceptance.
 * A negative total fails an assertion, so a replay reproduces it exactly when
 * the pinned module still has the bug.
 */
class MainActivity : ComponentActivity() {
    private val total = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val patch = (application as CapsuleLabApp).binding.patch
        val checkout = BugCapsule.action("lab.checkout") {
            val priced = patch.call(coupon, sample).value
            total.value = priced.total
            BugCapsule.check("order.total_positive", !priced.total.startsWith("-"), "negative order total")
        }
        setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.safeDrawingPadding()) {
                        Text("Total: ${total.value}")
                        Button(onClick = { checkout.run() }) { Text("Check out") }
                    }
                }
            }
        }
        if (!BugCapsule.isReplaying) capture(patch, checkout)
    }

    private fun capture(patch: BugCapsulePatch, checkout: Runnable) {
        val exchange = getExternalFilesDir(null)!!
        File(exchange, "inbox").listFiles().orEmpty().sorted().forEach { f ->
            Log.i("CapsuleLab", "stage ${f.name}: ${patch.stage(f.readBytes())}")
            f.delete()
        }
        patch.markHealthy("first_screen")
        window.decorView.post {
            checkout.run()
            Thread {
                Thread.sleep(300)
                BugCapsule.capture().get()?.file?.copyTo(File(exchange, "lab.bugcapsule"), overwrite = true)
                File(exchange, "capture.txt").writeText("${patch.status().opt("running")}")
            }.start()
        }
    }
}
