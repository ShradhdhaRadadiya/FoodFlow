package com.foodflow.feature.tracking

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Pure helper for validating delivery partner phone numbers and safely launching
 * Android's native dialer via [Intent.ACTION_DIAL] (requires zero CALL_PHONE permissions).
 */
object PhoneDialerHelper {

    /** Removes non-dialable characters and rejects implausible phone numbers. */
    fun sanitizeDialablePhone(rawPhone: String?): String? {
        if (rawPhone.isNullOrBlank()) return null
        val cleaned = buildString {
            rawPhone.trim().forEachIndexed { idx, ch ->
                when {
                    ch.isDigit() -> append(ch)
                    ch == '+' && idx == 0 -> append(ch)
                }
            }
        }
        val digitCount = cleaned.count { it.isDigit() }
        return if (digitCount in 7..15) cleaned else null
    }

    /** Creates a system dial intent when the phone number is valid. */
    fun buildDialIntent(rawPhone: String?): Intent? {
        val sanitized = sanitizeDialablePhone(rawPhone) ?: return null
        return Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$sanitized")
        }
    }

    /** Opens the system dialer and reports whether it could be launched. */
    fun launchDialer(context: Context, rawPhone: String?): Boolean {
        val intent = buildDialIntent(rawPhone) ?: return false
        return runCatching {
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
