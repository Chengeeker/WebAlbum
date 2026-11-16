package com.webdav.album

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AppLock(context: Context) {
    private val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    var revision by mutableIntStateOf(0)
        private set
    val pinEnabled: Boolean get() = prefs.getBoolean("pin_enabled", false)
    val biometricEnabled: Boolean get() = prefs.getBoolean("biometric_enabled", false)
    val enabled: Boolean get() = pinEnabled || biometricEnabled

    fun setPin(pin: String) {
        require(validPin(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = derivePin(pin, salt)
        check(prefs.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(digest, Base64.NO_WRAP))
            .putBoolean("pin_enabled", true).remove("failures").remove("blocked_at").commit())
        revision++
    }
    fun disablePin() {
        check(prefs.edit().putBoolean("pin_enabled", false).remove("salt").remove("hash").remove("failures").remove("blocked_at").commit())
        revision++
    }
    fun setBiometric(value: Boolean) { check(prefs.edit().putBoolean("biometric_enabled", value).commit()); revision++ }

    @Synchronized fun remainingSeconds(): Long {
        var started = prefs.getLong("blocked_at", -1)
        if (started < 0) return 0
        val now = SystemClock.elapsedRealtime()
        if (now < started) { started = now; check(prefs.edit().putLong("blocked_at", now).commit()) }
        val remaining = 30_000L - (now - started)
        if (remaining <= 0) { check(prefs.edit().remove("blocked_at").putInt("failures", 0).commit()); return 0 }
        return (remaining + 999) / 1000
    }
    @Synchronized fun verifyPin(pin: String): Boolean {
        if (!pinEnabled || remainingSeconds() > 0 || !validPin(pin)) return false
        val salt = prefs.getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.getString("hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val matches = MessageDigest.isEqual(expected, derivePin(pin, salt))
        if (matches) check(prefs.edit().putInt("failures", 0).remove("blocked_at").commit())
        else {
            val failures = prefs.getInt("failures", 0) + 1
            check(prefs.edit().putInt("failures", failures).apply {
                if (failures >= 5) putLong("blocked_at", SystemClock.elapsedRealtime())
            }.commit())
        }
        return matches
    }
}

fun validPin(pin: String) = pin.length in 4..12 && pin.all { it in '0'..'9' }

fun derivePin(pin: String, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(pin.toCharArray(), salt, 150_000, 256)
    return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
}
