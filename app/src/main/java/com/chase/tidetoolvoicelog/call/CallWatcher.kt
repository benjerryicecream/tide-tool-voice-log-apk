package com.chase.tidetoolvoicelog.call

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.chase.tidetoolvoicelog.data.Prefs
import java.util.concurrent.Executors

/**
 * Keeps the prompt on screen for the whole call and takes it away when the call
 * ends. Samsung's Record button only exists in the in-call screen, so dismissing
 * on answer would be the wrong moment.
 */
object CallWatcher {

    private var callback: Any? = null
    private var telephony: TelephonyManager? = null
    private var watching = false

    fun watchUntilCallEnds(context: Context) {
        if (watching) return
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        telephony = tm
        watching = true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        if (state == TelephonyManager.CALL_STATE_IDLE) finish(context)
                    }
                }
                callback = cb
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("kept for API 29/30")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        if (state == TelephonyManager.CALL_STATE_IDLE) finish(context)
                    }
                }
                callback = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: SecurityException) {
            watching = false
            callback = null
        } catch (e: Exception) {
            watching = false
            callback = null
        }
    }

    private fun finish(context: Context) {
        // Ignore an idle state delivered immediately on registration.
        val prefs = Prefs(context)
        if (System.currentTimeMillis() - prefs.promptArmedAt < 4000) return

        CallPrompt.dismiss(context)
        prefs.clearPrompt()
        stopWatching()
    }

    fun stopWatching() {
        val tm = telephony
        val cb = callback
        try {
            if (tm != null && cb != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cb is TelephonyCallback) {
                    tm.unregisterTelephonyCallback(cb)
                } else if (cb is PhoneStateListener) {
                    @Suppress("DEPRECATION")
                    tm.listen(cb, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            // already gone
        }
        callback = null
        telephony = null
        watching = false
    }
}
