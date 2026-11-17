package com.chase.tidetoolvoicelog.data

import android.content.Context
import org.json.JSONObject

/** Everything that lives in SharedPreferences: settings, the chosen business line, last-call diagnostics. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("tidetool", Context.MODE_PRIVATE)

    var aiBaseUrl: String
        get() = sp.getString("ai.base", "") ?: ""
        set(v) = sp.edit().putString("ai.base", v.trim()).apply()

    var aiKey: String
        get() = sp.getString("ai.key", "") ?: ""
        set(v) = sp.edit().putString("ai.key", v.trim()).apply()

    var aiModel: String
        get() = sp.getString("ai.model", "") ?: ""
        set(v) = sp.edit().putString("ai.model", v.trim()).apply()

    val aiReady: Boolean get() = aiBaseUrl.isNotEmpty() && aiKey.isNotEmpty() && aiModel.isNotEmpty()

    /** Import a new Samsung recording without asking. Off by default. */
    var autoImport: Boolean
        get() = sp.getBoolean("autoImport", false)
        set(v) = sp.edit().putBoolean("autoImport", v).apply()

    /**
     * The PhoneAccountHandle.id the user confirmed as the business line.
     * Android gives no public "which SIM slot rang" API, so this is the anchor
     * the whole 2nd-line detection hangs on.
     */
    var businessAccountId: String
        get() = sp.getString("line.accountId", "") ?: ""
        set(v) = sp.edit().putString("line.accountId", v).apply()

    var businessAccountLabel: String
        get() = sp.getString("line.label", "") ?: ""
        set(v) = sp.edit().putString("line.label", v).apply()

    /** Highest MediaStore id already offered, so a file is not imported twice. */
    var lastSeenMediaId: Long
        get() = sp.getLong("media.lastId", 0L)
        set(v) = sp.edit().putLong("media.lastId", v).apply()

    /** Session bookkeeping for "we saw this call ringing on the business line". */
    var promptArmedAt: Long
        get() = sp.getLong("call.armedAt", 0L)
        set(v) = sp.edit().putLong("call.armedAt", v).apply()

    var promptCaller: String
        get() = sp.getString("call.caller", "") ?: ""
        set(v) = sp.edit().putString("call.caller", v).apply()

    /* ---------------------------------------------------------- diagnostics */

    var lastCallJson: String
        get() = sp.getString("diag.lastCall", "") ?: ""
        set(v) = sp.edit().putString("diag.lastCall", v).apply()

    fun saveLastCall(rec: LastCall) {
        lastCallJson = rec.toJson().toString()
    }

    fun lastCall(): LastCall? = runCatching { LastCall.fromJson(JSONObject(lastCallJson)) }.getOrNull()

    /** Ringing on the business line, seen within the last few minutes. */
    fun promptIsFresh(): Boolean = System.currentTimeMillis() - promptArmedAt < 15 * 60 * 1000

    fun clearPrompt() {
        promptArmedAt = 0L
        promptCaller = ""
    }
}

/** Everything the Diagnostics screen shows about the last incoming call. */
data class LastCall(
    val at: Long,
    val number: String,
    val contactName: String,
    val accountId: String,
    val accountLabel: String,
    val subscriptionId: Int,
    val slotIndex: Int,
    val otherAccounts: String,
    val verdict: String,
    val prompted: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at)
        put("number", number)
        put("contactName", contactName)
        put("accountId", accountId)
        put("accountLabel", accountLabel)
        put("subscriptionId", subscriptionId)
        put("slotIndex", slotIndex)
        put("otherAccounts", otherAccounts)
        put("verdict", verdict)
        put("prompted", prompted)
    }

    companion object {
        fun fromJson(o: JSONObject): LastCall = LastCall(
            at = o.optLong("at"),
            number = o.optString("number"),
            contactName = o.optString("contactName"),
            accountId = o.optString("accountId"),
            accountLabel = o.optString("accountLabel"),
            subscriptionId = o.optInt("subscriptionId", -1),
            slotIndex = o.optInt("slotIndex", -1),
            otherAccounts = o.optString("otherAccounts"),
            verdict = o.optString("verdict"),
            prompted = o.optBoolean("prompted")
        )
    }
}
