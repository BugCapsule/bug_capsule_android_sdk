package dev.bugcapsule.android.internal

import dev.bugcapsule.android.ActionHandler
import dev.bugcapsule.android.TextSetter
import java.util.concurrent.ConcurrentHashMap

/**
 * Named UI actions and tracked text fields. Semantic replay invokes actions
 * by id; text fields are always restored by id. The latest registration wins
 * (a recreated screen re-registers its controls).
 */
class ActionRegistry {
    private val actions = ConcurrentHashMap<String, ActionHandler>()
    private val texts = ConcurrentHashMap<String, TextSetter>()

    fun register(id: String, handler: ActionHandler) {
        actions[id] = handler
    }

    fun unregister(id: String, handler: ActionHandler) {
        actions.remove(id, handler)
    }

    fun invoke(id: String, payload: Map<String, Any?>?): Boolean {
        val handler = actions[id] ?: return false
        handler.invoke(payload)
        return true
    }

    fun trackText(id: String, setter: TextSetter) {
        texts[id] = setter
    }

    fun untrackText(id: String, setter: TextSetter) {
        texts.remove(id, setter)
    }

    fun setText(id: String, value: String): Boolean {
        val setter = texts[id] ?: return false
        setter.setText(value)
        return true
    }

    fun clear() {
        actions.clear()
        texts.clear()
    }
}
