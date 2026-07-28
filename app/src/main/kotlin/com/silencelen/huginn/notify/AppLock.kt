package com.silencelen.huginn.notify

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Locking the app behind the device's own credential.
 *
 * This app is a remote hand on huginn: whoever holds an unlocked phone can run
 * commands in ~/netplan and read every session. The device lock already guards
 * that, but a phone handed over unlocked — a passenger picking music, a child
 * with a game — is an ordinary event, and this is not an ordinary app to leave
 * open in it.
 *
 * The androidx BiometricPrompt, and the history matters: the first version used
 * the FRAMEWORK prompt to dodge the library's FragmentActivity requirement, and
 * on the real device it silently failed — see [authenticate]. MainActivity is a
 * FragmentActivity now; the requirement was cheaper than the bug.
 *
 * The prompt allows biometrics OR the device PIN/pattern — the same set of proofs
 * that unlock the phone. Inventing a separate app password would be strictly
 * worse: one more secret, no recovery story, same attacker.
 */
object AppLock {

    /**
     * How long the app may sit in the background before coming back locked.
     *
     * Nonzero on purpose. Locking on every task-switch punishes the commonest
     * gesture in the app's real use — hopping to a terminal or browser and
     * straight back mid-conversation — and a lock that irritates gets turned off,
     * which protects nothing. One minute covers the handed-over-phone case this
     * exists for: the danger there is measured in minutes, not seconds.
     */
    const val GRACE_MS = 60_000L

    /** Process-wide, surviving activity recreation; reset by process death. */
    @Volatile var lastAwayAt: Long = 0L

    /** Cached so the ON_START decision never waits on DataStore. */
    @Volatile var enabledCache: Boolean = false

    /**
     * Whether coming to the foreground should demand an unlock.
     *
     * `awayAt == 0` means the process has never been backgrounded — a cold start —
     * and a cold start with the lock enabled always locks: process death erases
     * any memory of a recent unlock, and guessing in the user's favour here would
     * mean the lock quietly not applying exactly when the phone was out of their
     * hands long enough for the process to die.
     */
    fun shouldLock(enabled: Boolean, awayAt: Long, now: Long, graceMs: Long = GRACE_MS): Boolean {
        if (!enabled) return false
        if (awayAt == 0L) return true
        return now - awayAt >= graceMs
    }

    /** A lock is only offerable when the device has something to unlock WITH. */
    fun canLock(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    /**
     * Shows the system unlock sheet via the androidx library.
     *
     * The FIRST version used the framework BiometricPrompt to avoid the library's
     * FragmentActivity requirement — and on the actual device the lock never
     * visibly engaged: the prompt failed somewhere silent, and the failure path
     * "failed open", flipping the lock off before the first frame drew. Both
     * halves of that are corrected. The androidx library exists precisely to
     * absorb OEM prompt differences (MainActivity is a FragmentActivity now),
     * and failure fails CLOSED with a reason the lock screen can show — an
     * invisible security feature is indistinguishable from a missing one.
     *
     * @param onResult ok, plus a human reason when not ok (null for a plain
     *   cancel, which needs no explaining).
     */
    fun authenticate(activity: FragmentActivity, onResult: (Boolean, String?) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onResult(true, null)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    onResult(false, if (cancelled) null else "$errString ($errorCode)")
                }

                // A failed fingerprint read is not an outcome: the sheet stays up.
                override fun onAuthenticationFailed() = Unit
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Huginn is locked")
            .setSubtitle("Unlock with the same screen lock as the phone")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            // Fail CLOSED, visibly. The retry button and the reason are on the
            // lock screen; unlocking because the prompt broke would be a lock
            // that opens for whoever breaks it.
            onResult(false, e.message ?: "could not show the unlock prompt")
        }
    }
}
