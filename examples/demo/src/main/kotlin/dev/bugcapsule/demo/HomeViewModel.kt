package dev.bugcapsule.demo

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = false,
    val name: String? = null,
    val tier: String? = null,
    val picks: List<String> = emptyList(),
)

class HomeViewModel : ViewModel() {
    private val _state = MutableLiveData(HomeState())
    val state: LiveData<HomeState> = _state
    private var profile: Profile? = null

    /**
     * Bug 2 (timing-sensitive network): profile and recommendations load in
     * parallel; the recommendations handler assumes the profile is there.
     * With the fake server's latencies the recommendations win ~1 in 4 runs
     * and the app crashes.
     */
    fun refresh() {
        profile = null
        _state.value = HomeState(loading = true)
        if (DemoFixes.race) {
            viewModelScope.launch {
                val p = async { ShopNetwork.api.profile() }
                val r = async { ShopNetwork.api.recommendations() }
                profile = p.await()
                show(r.await())
            }
            return
        }
        viewModelScope.launch {
            profile = ShopNetwork.api.profile()
            _state.value = _state.value!!.copy(name = profile?.name, tier = profile?.tier)
        }
        viewModelScope.launch {
            val recommendations = ShopNetwork.api.recommendations()
            show(recommendations)
        }
    }

    private fun show(recommendations: Recommendations) {
        val p = checkNotNull(profile) { "Recommendations arrived before the profile" }
        val picks = recommendations.items.orEmpty().map { if (p.tier == "gold") "★ $it" else it }
        _state.value = HomeState(loading = false, name = p.name, tier = p.tier, picks = picks)
    }
}
