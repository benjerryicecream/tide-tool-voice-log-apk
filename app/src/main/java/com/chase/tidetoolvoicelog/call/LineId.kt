package com.chase.tidetoolvoicelog.call

import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Which line did the call arrive on?
 *
 * Android has no public API that says "this call rang on SIM slot 2". The only
 * handle we get is [android.telecom.Call.Details.getAccountHandle], and turning
 * that into a slot is inference:
 *
 *  - a PhoneAccountHandle.id is, on stock Android and on One UI, the subscription
 *    id as a string; when that matches an entry in SubscriptionManager we get the
 *    real slot index,
 *  - otherwise we fall back to "position in callCapablePhoneAccounts", which is
 *    close but not guaranteed,
 *  - the user can pin the business line once in Setup, and that pinned id is what
 *    the prompt actually keys on.
 *
 * Everything this file works out is written to Diagnostics so a wrong guess is
 * visible instead of silent.
 */
object LineId {

    private const val TAG = "LineId"

    data class AccountInfo(
        val handleId: String,
        val label: String,
        val subscriptionId: Int,
        val slotIndex: Int,
        val index: Int
    ) {
        val slotText: String get() = if (slotIndex >= 0) "SIM ${slotIndex + 1}" else "slot unknown"
        val subText: String get() = if (subscriptionId >= 0) subscriptionId.toString() else "unknown"
    }

    enum class Verdict {
        /** handle matches the line the user pinned as the business line */
        BUSINESS,
        /** handle is a known account, but not the business one */
        OTHER_LINE,
        /** we could not tell - no prompt, by design */
        UNKNOWN
    }

    data class Result(
        val verdict: Verdict,
        val account: AccountInfo?,
        val accounts: List<AccountInfo>,
        val note: String
    )

    fun accounts(context: Context): List<AccountInfo> {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
        val handles: List<PhoneAccountHandle> = try {
            tm.callCapablePhoneAccounts
        } catch (e: Exception) {
            Log.w(TAG, "callCapablePhoneAccounts failed", e)
            emptyList()
        }
        val subs = try {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "no READ_PHONE_STATE for subscriptions", e)
            emptyList()
        }

        return handles.mapIndexed { index, h ->
            val id = h.id ?: ""
            val byId = subs.firstOrNull { it.subscriptionId.toString() == id }
            val byIndex = subs.getOrNull(index)
            val sub = byId ?: byIndex
            AccountInfo(
                handleId = id,
                label = labelOf(context, h),
                subscriptionId = sub?.subscriptionId ?: -1,
                slotIndex = sub?.simSlotIndex ?: -1,
                index = index
            )
        }
    }

    private fun labelOf(context: Context, handle: PhoneAccountHandle): String {
        return try {
            val tm = context.getSystemService(TelecomManager::class.java)
            val account = tm?.getPhoneAccount(handle)
            val label = account?.label?.toString()?.trim().orEmpty()
            if (label.isNotEmpty()) label else handle.id ?: "account"
        } catch (e: Exception) {
            handle.id ?: "account"
        }
    }

    /**
     * @param businessAccountId the handle id the user pinned in Setup ("" when unset)
     */
    fun resolve(context: Context, handle: PhoneAccountHandle?, businessAccountId: String): Result {
        val all = accounts(context)
        if (handle == null) {
            return Result(Verdict.UNKNOWN, null, all, "the call carried no phone account handle")
        }
        val id = handle.id ?: ""
        val account = all.firstOrNull { it.handleId == id }

        if (businessAccountId.isEmpty()) {
            return Result(
                Verdict.UNKNOWN, account, all,
                "no business line pinned yet - open Setup and pick the Verizon eSIM"
            )
        }
        if (id == businessAccountId) {
            return Result(Verdict.BUSINESS, account, all, "handle matches the pinned business line")
        }
        val pinnedKnown = all.any { it.handleId == businessAccountId }
        return Result(
            Verdict.OTHER_LINE, account, all,
            if (pinnedKnown) "handle is another line" else "the pinned business line is not on this phone right now"
        )
    }

    /** Best guess for the Setup screen: the second account, or one that says Verizon. */
    fun suggestBusiness(context: Context): AccountInfo? {
        val all = accounts(context)
        if (all.isEmpty()) return null
        all.firstOrNull { a ->
            val l = a.label.lowercase()
            a.slotIndex == 1 || l.contains("verizon") || l.contains("business")
        }?.let { return it }
        return all.getOrNull(1)
    }

    fun describe(accounts: List<AccountInfo>): String =
        accounts.joinToString("; ") { "${it.label} [id=${it.handleId} sub=${it.subText} ${it.slotText}]" }
}
