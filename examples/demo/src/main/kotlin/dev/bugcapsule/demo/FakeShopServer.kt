package dev.bugcapsule.demo

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

/**
 * Simulated shop backend inside the app. It behaves like a production
 * backend from the app's point of view: latency varies and data differs
 * between calls (unseeded randomness). That nondeterminism is what makes the
 * planted bugs "cannot reproduce", and what BugCapsule records.
 */
class FakeShopServer(private val random: Random = Random()) : Interceptor {
    private val catalog = listOf(
        Triple("mug", "Android mug", 12.5),
        Triple("tee", "Droid T-shirt", 24.0),
        Triple("cap", "BugCapsule cap", 18.0),
        Triple("sticker", "Sticker pack", 4.0),
        Triple("hoodie", "Kotlin hoodie", 55.0),
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val (status, body) = when (request.url.encodedPath) {
            "/api/profile" -> {
                latency(100, 600)
                200 to JSONObject().put("name", "Sam").put("tier", if (random.nextBoolean()) "gold" else "silver")
            }
            "/api/recommendations" -> {
                latency(200, 800)
                200 to JSONObject().put("items", JSONArray(catalog.shuffled(random).take(3).map { it.second }))
            }
            "/api/cart" -> {
                latency(80, 400)
                val items = JSONArray()
                for ((sku, name, price) in catalog.shuffled(random).take(1 + random.nextInt(3))) {
                    items.put(JSONObject().put("sku", sku).put("name", name).put("price", price).put("qty", 1 + random.nextInt(2)))
                }
                // 1 in 4 carts contain a discontinued item whose price is null.
                if (random.nextInt(4) == 0) {
                    items.put(JSONObject().put("sku", "legacy-pin").put("name", "Legacy pin (discontinued)").put("price", JSONObject.NULL).put("qty", 1))
                }
                200 to JSONObject().put("cartId", "c_${random.nextInt(99999)}").put("items", items)
            }
            "/api/checkout" -> {
                latency(150, 500)
                200 to JSONObject().put("orderId", "o_${random.nextInt(99999)}").put("status", "paid")
            }
            "/api/login" -> {
                latency(100, 300)
                200 to JSONObject().put("token", "tok_${random.nextInt(999999)}").put("name", "Sam")
            }
            else -> 404 to JSONObject().put("error", "not found")
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(if (status == 200) "OK" else "Not Found")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Set-Cookie", "session=abc123; Path=/")
            .body(body.toString().toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private fun latency(minMs: Int, maxMs: Int) = Thread.sleep((minMs + random.nextInt(maxMs - minMs)).toLong())
}
