package dev.bugcapsule.patch.compose

import org.json.JSONObject

/** Check every node before rendering anything, including offscreen list children. */
internal fun surfaceImagesAvailable(root: JSONObject, available: Set<String>): Boolean {
    var nodes = 0
    fun visit(node: JSONObject, depth: Int): Boolean {
        if (++nodes > 512 || depth > 16) return false
        if (node.optString("type") == "image" &&
            (node.opt("asset") !is String || node.getString("asset") !in available || node.opt("label") !is String)) return false
        val children = node.optJSONArray("children") ?: return true
        for (i in 0 until children.length()) {
            if (!visit(children.optJSONObject(i) ?: return false, depth + 1)) return false
        }
        return true
    }
    return visit(root, 0)
}
