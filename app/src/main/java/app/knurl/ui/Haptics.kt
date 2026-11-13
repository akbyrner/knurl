package app.knurl.ui

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The wheel's feel. This talks to the vibration motor directly instead of using the system's
 * built-in "tick", which is deliberately faint. Strength follows Android's own Touch feedback
 * setting (Settings > Sound & vibration > Vibration & haptics), so turn that up too if it still feels light.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private val hasClickPrimitive = Build.VERSION.SDK_INT >= 30 &&
        vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)

    /** One notch of the wheel: the motor's sharpest click at the strength below. About 33 ms long. */
    fun tick() {
        val effect = when {
            hasClickPrimitive ->
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, TICK_STRENGTH)
                    .compose()
            Build.VERSION.SDK_INT >= 29 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            else -> VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        play(effect)
    }

    /** A button press: Menu, Select, Next, Previous, Play/Pause. The system's heavy click, still short. */
    fun press() {
        val effect = if (Build.VERSION.SDK_INT >= 29) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        play(effect)
    }

    private fun play(effect: VibrationEffect) {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            vibrator.vibrate(effect)
        }
    }

    private companion object {
        /** 0..1. The strongest click the motor offers is 1.0. */
        const val TICK_STRENGTH = 1.0f
    }
}
