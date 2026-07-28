package com.silencelen.huginn.notify

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Locking the app behind the device's own credential.
 *
 * This app is a remote hand on huginn: whoever holds an unlocked phone can run
 * commands in ~/netplan and read every session. The device lock already guards
 * that, but a phone handed over unlocked — a passenger picking music, a child
 * with a game — is an ordinary event, and this is not an ordinary app to leave
 * open in it.
 *
 * The FRAMEWORK BiometricPrompt rather than the androidx library, deliberately:
 * the androidx one requires the activity to be a FragmentActivity, and this app's
 * single activity is a plain ComponentActivity. minSdk is 29, where the framework
 * prompt already exists; the one API difference (how a credential fallback is
 * requested) is handled below.
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
     * Shows the system unlock sheet. The callback fires exactly once.
     *
     * Cancellation (back gesture, tapping outside) reports failure and the app
     * simply stays locked — there is deliberately no attempt counting or lockout
     * here, because the credential layer underneath already has its own.
     */
    fun authenticate(activity: Activity, onResult: (Boolean) -> Unit) {
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Huginn is locked")
            .setSubtitle("Unlock with the same screen lock as the phone")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }

        var done = false
        fun finish(ok: Boolean) {
            if (!done) { done = true; onResult(ok) }
        }
        runCatching {
            builder.build().authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) =
                        finish(true)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) =
                        finish(false)

                    // A failed fingerprint read is not an outcome: the sheet stays up
                    // and the user tries again, so nothing to report yet.
                    override fun onAuthenticationFailed() = Unit
                },
            )
        }.onFailure {
            // No prompt could be shown at all (no hardware, mid-teardown activity).
            // Failing CLOSED here would brick the app on such devices; the device
            // lock itself is still in front of everything.
            finish(true)
        }
    }
}
