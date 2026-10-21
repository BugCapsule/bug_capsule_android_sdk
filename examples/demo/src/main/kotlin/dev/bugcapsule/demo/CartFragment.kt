package dev.bugcapsule.demo

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dev.bugcapsule.android.views.BugCapsuleViews
import java.util.Locale

class CartFragment : Fragment(R.layout.fragment_cart) {
    private val viewModel: CartViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val items = view.findViewById<TextView>(R.id.items)
        val total = view.findViewById<TextView>(R.id.total)
        val status = view.findViewById<TextView>(R.id.cart_status)
        val coupon = view.findViewById<EditText>(R.id.coupon)
        BugCapsuleViews.trackText(coupon, "cart.coupon")
        BugCapsuleViews.onClick(view.findViewById(R.id.apply_coupon), "cart.applyCoupon") {
            viewModel.applyCoupon(coupon.text.toString())
        }
        BugCapsuleViews.onClick(view.findViewById(R.id.checkout), "cart.checkout") { viewModel.checkout() }
        viewModel.state.observe(viewLifecycleOwner) { s ->
            items.text = if (s.items.isEmpty()) {
                if (s.status == "loading") "Loading cart…" else "Your cart is empty"
            } else {
                s.items.joinToString("\n") { "${it.qty} × ${it.name} — ${it.price?.let { p -> String.format(Locale.US, "%.2f", p) } ?: "n/a"}" }
            }
            total.text = String.format(Locale.US, "Total: %.2f%s", s.total, if (s.coupon != null) " (coupon ${s.coupon})" else "")
            status.text = s.message ?: s.status
        }
    }
}
