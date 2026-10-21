package dev.bugcapsule.demo

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.views.BugCapsuleViews
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.fragment_home) {
    private val viewModel: HomeViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val greeting = view.findViewById<TextView>(R.id.greeting)
        val picks = view.findViewById<TextView>(R.id.picks)
        val status = view.findViewById<TextView>(R.id.status)
        BugCapsuleViews.onClick(view.findViewById(R.id.refresh), "home.refresh") { viewModel.refresh() }
        BugCapsuleViews.onClick(view.findViewById(R.id.open_cart), "home.openCart") {
            findNavController().navigate(R.id.cart, Bundle().apply { putString("source", "home") })
        }
        BugCapsuleViews.onClick(view.findViewById(R.id.account), "home.account") { findNavController().navigate(R.id.account) }
        BugCapsuleViews.onClick(view.findViewById(R.id.simulate_upgrade), "home.simulateUpgrade") {
            CartCache.writeLegacy(requireContext())
            BugCapsule.log("wrote a v0.9 cart cache")
            status.text = "Legacy v0.9 cart written. Restart the app, then open the cart."
        }
        viewModel.state.observe(viewLifecycleOwner) { s ->
            greeting.text = if (s.name == null) "Welcome" else "Welcome back, ${s.name} (${s.tier})"
            picks.text = when {
                s.loading -> "Loading…"
                s.picks.isEmpty() -> "Tap refresh to load your picks"
                else -> "Picks for ${s.tier}: " + s.picks.joinToString(", ")
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            status.text = "Prices in ${SettingsStore.currency(requireContext())}"
        }
    }
}
