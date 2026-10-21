package dev.bugcapsule.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dev.bugcapsule.android.BugCapsule
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class CartItem(val sku: String, val name: String, val price: Double?, val qty: Int)

data class CartState(
    val status: String = "loading",
    val cartId: String? = null,
    val items: List<CartItem> = emptyList(),
    val coupon: String? = null,
    val orderId: String? = null,
    val message: String? = null,
) {
    val subtotal: Double get() = items.sumOf { (it.price ?: 0.0) * it.qty }
    val discount: Double
        get() = when (coupon) {
            "SAVE10" -> subtotal * 0.1
            // A fixed discount larger than the cart makes the total negative.
            "SAVE100" -> 100.0
            else -> 0.0
        }
    val total: Double get() = subtotal - discount

    fun toJson(): JSONObject = JSONObject()
        .put("status", status)
        .put("cartId", cartId ?: JSONObject.NULL)
        .put("coupon", coupon ?: JSONObject.NULL)
        .put("orderId", orderId ?: JSONObject.NULL)
        .put("items", JSONArray(items.map {
            JSONObject().put("sku", it.sku).put("name", it.name).put("price", it.price ?: JSONObject.NULL).put("qty", it.qty)
        }))

    companion object {
        fun fromJson(j: JSONObject): CartState {
            val items = j.optJSONArray("items") ?: JSONArray()
            return CartState(
                status = j.optString("status", "ready"),
                cartId = j.optString("cartId").takeIf { it.isNotEmpty() && it != "null" },
                coupon = j.optString("coupon").takeIf { it.isNotEmpty() && it != "null" },
                items = (0 until items.length()).map { i ->
                    val o = items.getJSONObject(i)
                    CartItem(o.getString("sku"), o.getString("name"), if (o.isNull("price")) null else o.getDouble("price"), o.getInt("qty"))
                },
            )
        }
    }
}

class CartViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableLiveData(CartState())
    val state: LiveData<CartState> = _state
    private val current: CartState get() = _state.value!!

    init {
        // Registered state: serialized into periodic checkpoints, restored in replay.
        BugCapsule.registerViewModel(this, "cart", { current.toJson().toString() }) { value ->
            (value as? String)?.let { _state.value = CartState.fromJson(JSONObject(it)) }
        }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val cached = CartCache.read(getApplication())
            if (cached != null) {
                // Bug 3: a v0.9 legacy cache (a JSON array) crashes here.
                _state.value = CartState.fromJson(CartCache.parse(cached)).copy(status = "loading")
            }
            try {
                val dto = ShopNetwork.api.cart()
                val next = CartState(
                    status = "ready",
                    cartId = dto.cartId,
                    items = dto.items.orEmpty().map { CartItem(it.sku, it.name, it.price, it.qty) },
                )
                CartCache.write(getApplication(), next.toJson().toString())
                _state.value = next
                BugCapsule.reportState("cart")
            } catch (e: Exception) {
                _state.value = current.copy(status = "error", message = e.message)
            }
        }
    }

    fun applyCoupon(code: String) {
        val next = current.copy(coupon = code.trim().uppercase())
        BugCapsule.check("cart_total_non_negative", next.total >= 0, "total ${next.total} with coupon ${next.coupon}")
        _state.value = next
    }

    /**
     * Bug 1 (data-dependent): a discontinued item has `price: null` in some
     * server responses; checkout requires every price. The error is caught,
     * shown, and reported to BugCapsule explicitly.
     */
    fun checkout() {
        val s = current
        try {
            val priced = if (DemoFixes.price) s.items.filter { it.price != null } else s.items
            val amount = priced.sumOf { checkNotNull(it.price) { "Item ${it.sku} has no price" } * it.qty } - s.discount
            _state.value = s.copy(status = "checkingOut")
            viewModelScope.launch {
                val result = ShopNetwork.api.checkout(
                    CheckoutRequest(s.cartId, amount, BugCapsule.currentTimeMillis(), BugCapsule.random().nextInt(1 shl 30)),
                )
                _state.value = current.copy(status = "paid", orderId = result.orderId, message = "Order ${result.orderId} paid")
            }
        } catch (e: IllegalStateException) {
            BugCapsule.reportError(e)
            _state.value = s.copy(status = "error", message = "Checkout failed: ${e.message}")
        }
    }
}
