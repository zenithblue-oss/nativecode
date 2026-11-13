package com.zenithblue.nativecode.telemetry

import android.app.Activity
import android.app.AlertDialog

/** One consent dialog for onboarding, the one-time prompt for existing users, and Settings. */
object ConsentSheet {
    /** Both boxes start at the stored choice (off until the user opts in). */
    fun show(activity: Activity, onDone: () -> Unit = {}) {
        val checked = booleanArrayOf(Telemetry.crashConsent(activity), Telemetry.usageConsent(activity))
        AlertDialog.Builder(activity)
            .setTitle("Help improve NativeCode (optional)")
            .setMultiChoiceItems(
                arrayOf("Send crash reports", "Share anonymous usage statistics"),
                checked
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ -> Telemetry.setConsent(activity, checked[0], checked[1]) }
            // Dismiss without Save (back / outside tap) keeps the stored choice but stops re-asking.
            .setOnDismissListener {
                if (!Telemetry.asked(activity)) {
                    Telemetry.setConsent(activity, Telemetry.crashConsent(activity), Telemetry.usageConsent(activity))
                }
                onDone()
            }
            .show()
    }

    /** Existing users see the dialog once after upgrading. */
    fun showOnceIfUnasked(activity: Activity) {
        if (!Telemetry.asked(activity)) show(activity)
    }
}
