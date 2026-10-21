package dev.bugcapsule.android.views

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import dev.bugcapsule.android.ActionHandler
import dev.bugcapsule.android.BugCapsule

/**
 * Android Views helpers: semantic actions on clickable views and tracked
 * [EditText] fields.
 *
 * ```kotlin
 * BugCapsuleViews.onClick(checkoutButton, "cart.checkout") { viewModel.checkout() }
 * BugCapsuleViews.trackText(couponField, "cart.coupon")
 * BugCapsuleViews.trackText(passwordField, "login.password") // forced sensitive
 * ```
 */
object BugCapsuleViews {
    /**
     * Sets [listener] as the click listener of [view], recording the action
     * [actionId]; semantic replay invokes [listener] with [view].
     */
    @JvmStatic
    fun onClick(view: View, actionId: String, listener: View.OnClickListener) {
        BugCapsule.registerAction(actionId, ActionHandler { listener.onClick(view) })
        view.setOnClickListener { v ->
            BugCapsule.recordAction(actionId)
            listener.onClick(v)
        }
    }

    /**
     * Records edits of [editText] under [id] and restores them in replay.
     * Password input types are always treated as sensitive (their values are
     * never recorded, only the placeholder).
     */
    @JvmStatic
    @JvmOverloads
    fun trackText(editText: EditText, id: String, sensitive: Boolean = false): BugCapsule.TextTracker {
        val forced = sensitive || isPasswordInput(editText)
        var replaying = false
        val tracker = BugCapsule.trackText(id, { value ->
            replaying = true
            try {
                editText.setText(value)
                editText.setSelection(editText.text?.length ?: 0)
            } finally {
                replaying = false
            }
        }, forced)
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!replaying) tracker.onChanged(s?.toString() ?: "")
            }
        })
        return tracker
    }

    /** True for `textPassword`, `textVisiblePassword`, `textWebPassword` and `numberPassword`. */
    @JvmStatic
    fun isPasswordInput(view: TextView): Boolean {
        val type = view.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
}
