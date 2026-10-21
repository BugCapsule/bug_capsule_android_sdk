package dev.bugcapsule.android.feedback

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import dev.bugcapsule.android.BugCapsule
import kotlin.math.sqrt

/** Texts of the feedback form (override for localization). */
class FeedbackTexts @JvmOverloads constructor(
    val title: String = "Report a problem",
    val messageHint: String = "What happened?",
    val nameHint: String = "Name (optional)",
    val emailHint: String = "Email (optional)",
    val submit: String = "Send",
    val cancel: String = "Cancel",
    val thanks: String = "Thanks for the report",
)

/**
 * The ready-made feedback form for Views apps (monitoring-v1 §9): message,
 * optional name and email; "Send" calls [BugCapsule.captureFeedback] (which
 * also writes a capsule when `captureCapsuleWithFeedback` applies).
 *
 * ```kotlin
 * BugCapsuleFeedbackDialog.show(activity)
 * BugCapsuleFeedbackDialog.show(activity, eventId = BugCapsule.lastEventId())
 * ```
 */
object BugCapsuleFeedbackDialog {
    @Volatile
    private var showing: AlertDialog? = null

    /** Shows the form; returns the dialog (null when one is already showing). Main thread. */
    @JvmStatic
    @JvmOverloads
    fun show(
        activity: Activity,
        eventId: String? = null,
        texts: FeedbackTexts = FeedbackTexts(),
        name: String? = null,
        email: String? = null,
        onSubmitted: ((feedbackId: String?) -> Unit)? = null,
    ): AlertDialog? {
        if (showing?.isShowing == true || activity.isFinishing) return null
        val pad = dp(activity, 20)
        val message = EditText(activity).apply {
            hint = texts.messageHint
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            tag = "bugcapsule.feedback.message"
        }
        val nameField = EditText(activity).apply {
            hint = texts.nameHint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setText(name ?: "")
            tag = "bugcapsule.feedback.name"
        }
        val emailField = EditText(activity).apply {
            hint = texts.emailHint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(email ?: "")
            tag = "bugcapsule.feedback.email"
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(message)
            addView(nameField)
            addView(emailField)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(texts.title)
            .setView(form)
            .setNegativeButton(texts.cancel, null)
            .setPositiveButton(texts.submit, null)
            .create()
        dialog.setOnDismissListener { if (showing === dialog) showing = null }
        showing = dialog
        dialog.show()
        // Replaces the default listener so an empty message keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = message.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                message.error = texts.messageHint
                return@setOnClickListener
            }
            val id = BugCapsule.captureFeedback(
                text,
                nameField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                emailField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                eventId,
            )
            dialog.dismiss()
            try {
                Toast.makeText(activity, texts.thanks, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
            onSubmitted?.invoke(id)
        }
        return dialog
    }

    private fun dp(context: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}

/**
 * "Shake to report": calls [onShake] when the device is shaken (acceleration
 * over [thresholdG] g twice within [windowMs]), at most once per [cooldownMs].
 */
class ShakeDetector @JvmOverloads constructor(
    private val onShake: Runnable,
    private val thresholdG: Double = 2.7,
    private val windowMs: Long = 500,
    private val cooldownMs: Long = 2_000,
) : SensorEventListener {
    private var firstPeak = 0L
    private var lastShake = Long.MIN_VALUE / 2
    private var manager: SensorManager? = null

    fun start(context: Context): Boolean {
        val m = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val sensor = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        manager = m
        return m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        manager?.unregisterListener(this)
        manager = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        onAcceleration(event.values[0], event.values[1], event.values[2], System.currentTimeMillis())
    }

    /** One accelerometer sample in m/s² (tests feed samples directly). */
    fun onAcceleration(x: Float, y: Float, z: Float, nowMs: Long) {
        val g = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        if (g < thresholdG) return
        if (nowMs - lastShake < cooldownMs) return
        if (firstPeak == 0L || nowMs - firstPeak > windowMs) {
            firstPeak = nowMs
            return
        }
        firstPeak = 0
        lastShake = nowMs
        onShake.run()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
