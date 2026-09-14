package com.zenithblue.nativecode.cliauth

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens optional third-party vendor safety email links. */
object ReportMailHelper {

    private const val BODY_MAX = 3500

    fun openVendorMailto(ctx: Context, address: String, toolLabel: String): Boolean {
        val subject = "[NativeCode] Safety report — $toolLabel"
        val body =
            "I am reporting a concern related to $toolLabel used via NativeCode " +
                "(Android AI CLI launcher).\n\nDescription:\n"
        return openMailto(ctx, address = address, subject = subject, body = body.take(BODY_MAX))
    }

    private fun openMailto(
        ctx: Context,
        address: String,
        subject: String,
        body: String
    ): Boolean {
        return try {
            val uri = Uri.parse(
                "mailto:${Uri.encode(address)}" +
                    "?subject=${Uri.encode(subject)}" +
                    "&body=${Uri.encode(body)}"
            )
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, "Send report email"))
            true
        } catch (_: Exception) {
            false
        }
    }
}
