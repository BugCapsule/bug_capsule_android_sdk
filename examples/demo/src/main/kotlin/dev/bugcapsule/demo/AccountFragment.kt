package dev.bugcapsule.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import dev.bugcapsule.compose.rememberBugCapsuleAction
import dev.bugcapsule.compose.rememberTrackedText
import kotlinx.coroutines.launch

/** Compose screen: tracked email, sensitive password, semantic sign-in action. */
class AccountFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply { setContent { MaterialTheme { AccountScreen() } } }
}

@Composable
fun AccountScreen(onSignedIn: (String) -> Unit = {}) {
    val email = rememberTrackedText("account.email")
    val password = rememberTrackedText("account.password", sensitive = true)
    var status by remember { mutableStateOf("Signed out") }
    val scope = rememberCoroutineScope()
    val signIn = rememberBugCapsuleAction("account.signIn") {
        status = "Signing in…"
        scope.launch {
            status = try {
                val res = ShopNetwork.api.login(LoginRequest(email.value, password.value))
                onSignedIn(res.name ?: "")
                "Signed in as ${res.name}"
            } catch (e: Exception) {
                "Sign-in failed: ${e.message}"
            }
        }
    }
    Column(Modifier.padding(24.dp)) {
        OutlinedTextField(
            value = email.value,
            onValueChange = email::onValueChange,
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth().testTag("email"),
        )
        OutlinedTextField(
            value = password.value,
            onValueChange = password::onValueChange,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("password"),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = signIn, modifier = Modifier.fillMaxWidth().testTag("signIn")) { Text("Sign in") }
        Spacer(Modifier.height(16.dp))
        Text(status, modifier = Modifier.testTag("status"))
    }
}
