package dev.bugcapsule.patch.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.bugcapsule.patch.BugCapsulePatch
import org.json.JSONObject

/**
 * Renders the patched layout of a registered surface with shipped components,
 * or [bundled] when this generation has none. Actions run the app's handlers.
 */
@Composable
fun PatchSurfaceView(
    patch: BugCapsulePatch,
    id: String,
    actions: Map<String, () -> Unit>,
    onTextChanged: (action: String, id: String, value: String) -> Unit = { _, _, _ -> },
    bundled: @Composable () -> Unit,
) {
    PatchSurfaceView(patch, id, actions, emptyMap(), onTextChanged, bundled)
}

/**
 * [images] maps registered asset IDs to images supplied by the app. The renderer
 * never decodes patch bytes or loads URLs. If any referenced image is missing,
 * the entire [bundled] surface is shown, including its actions.
 */
@Composable
fun PatchSurfaceView(
    patch: BugCapsulePatch,
    id: String,
    actions: Map<String, () -> Unit>,
    images: Map<String, ImageBitmap>,
    onTextChanged: (action: String, id: String, value: String) -> Unit = { _, _, _ -> },
    bundled: @Composable () -> Unit,
) {
    val root = patch.surface(id)?.optJSONObject("root")
    val suppliedImages = images.toMap()
    if (root == null || !surfaceImagesAvailable(root, suppliedImages.keys)) bundled()
    else Node(root, actions, suppliedImages, onTextChanged)
}

@Composable
private fun Node(n: JSONObject, actions: Map<String, () -> Unit>, images: Map<String, ImageBitmap>, onText: (String, String, String) -> Unit) {
    val spacing = n.optInt("spacing", 0).dp
    val kids = n.optJSONArray("children")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
    when (n.getString("type")) {
        "text" -> Text(
            n.getString("text"),
            style = when (n.optString("style")) {
                "title" -> MaterialTheme.typography.titleLarge
                "caption" -> MaterialTheme.typography.bodySmall
                else -> MaterialTheme.typography.bodyMedium
            },
        )
        "image" -> Image(bitmap = images.getValue(n.getString("asset")), contentDescription = n.getString("label"))
        "spacer" -> Spacer(Modifier.size(n.getInt("size").dp))
        "button" -> Button(onClick = actions[n.getString("action")] ?: {}) { Text(n.getString("label")) }
        "text_field" -> {
            var value by rememberSaveable(n.getString("id")) { mutableStateOf("") }
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    onText(n.getString("action"), n.getString("id"), it)
                },
                label = { Text(n.getString("label")) },
            )
        }
        "row" -> Row(horizontalArrangement = Arrangement.spacedBy(spacing)) { kids.forEach { Node(it, actions, images, onText) } }
        "list" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing)) { items(kids.size) { Node(kids[it], actions, images, onText) } }
        else -> Column(verticalArrangement = Arrangement.spacedBy(spacing)) { kids.forEach { Node(it, actions, images, onText) } }
    }
}
