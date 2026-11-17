package com.chase.tidetoolvoicelog.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.chase.tidetoolvoicelog.data.LastCall
import com.chase.tidetoolvoicelog.data.Prefs

/**
 * Fires before the phone rings. It only ever *identifies* the call:
 * respondToCall() always allows it, and nothing here touches audio.
 *
 * If the line cannot be pinned down the service stays silent - a prompt on the
 * personal line would be worse than a missed one.
 */
class TideCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // Must answer within 5 seconds. Never block, reject or silence.
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)

        try {
            handle(callDetails)
        } catch (t: Throwable) {
            Log.w(TAG, "screening failed", t)
        }
    }

    private fun handle(callDetails: Call.Details) {
        val app = applicationContext
        val prefs = Prefs(app)

        val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) callDetails.accountHandle else null
        val number = callDetails.handle?.schemeSpecificPart?.let { Uri.decode(it) }.orEmpty()
        val digits = com.chase.tidetoolvoicelog.util.Phone.digits(number)

        val contactName = lookupContactName(app, digits)
        val result = LineId.resolve(app, handle, prefs.businessAccountId)

        val caller = when {
            contactName.isNotEmpty() && digits.isNotEmpty() -> "$contactName - ${com.chase.tidetoolvoicelog.util.Phone.pretty(digits)}"
            contactName.isNotEmpty() -> contactName
            digits.isNotEmpty() -> com.chase.tidetoolvoicelog.util.Phone.pretty(digits)
            else -> ""
        }

        val prompted = result.verdict == LineId.Verdict.BUSINESS

        prefs.saveLastCall(
            LastCall(
                at = System.currentTimeMillis(),
                number = number,
                contactName = contactName,
                accountId = handle?.id ?: "",
                accountLabel = result.account?.label ?: "",
                subscriptionId = result.account?.subscriptionId ?: -1,
                slotIndex = result.account?.slotIndex ?: -1,
                otherAccounts = LineId.describe(result.accounts),
                verdict = result.verdict.name + " - " + result.note,
                prompted = prompted
            )
        )

        if (prompted) {
            prefs.promptArmedAt = System.currentTimeMillis()
            prefs.promptCaller = caller
            CallPrompt.show(app, caller)
            CallWatcher.watchUntilCallEnds(app)
        }
    }

    private fun lookupContactName(context: Context, digits: String): String {
        if (digits.isEmpty()) return ""
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ""
        }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(digits))
        return try {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" } ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "contact lookup failed", e)
            ""
        }
    }

    companion object {
        private const val TAG = "ScreeningService"
    }
}

/** Notification actions. Neither one touches the call - they only open a screen or hide the prompt. */
class PromptReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_OPEN_PHONE -> {
                // ACTION_DIAL opens the phone app; during a call Samsung shows its in-call screen.
                val open = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                try {
                    context.startActivity(open)
                } catch (e: Exception) {
                    Log.w("PromptReceiver", "no dialer to open", e)
                }
            }

            ACTION_DISMISS -> {
                CallPrompt.dismiss(context)
                Prefs(context).clearPrompt()
                CallWatcher.stopWatching()
            }
        }
    }

    companion object {
        const val ACTION_OPEN_PHONE = "com.chase.tidetoolvoicelog.OPEN_PHONE"
        const val ACTION_DISMISS = "com.chase.tidetoolvoicelog.DISMISS"
    }
}
