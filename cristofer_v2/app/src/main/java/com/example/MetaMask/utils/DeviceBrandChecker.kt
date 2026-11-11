package com.example.MetaMask.utils

import java.lang.reflect.Method

object DeviceBrandChecker {

    private val RESTRICTED_FIRMWARE_PROPERTIES = setOf(
        "ro.mi.os.version.name",
        "ro.miui.ui.version.name",
        "ro.build.version.opporom",
        "ro.build.version.realmerom",
        "ro.oxygen.version",
        "ro.vivo.os.version",
        "ro.vivo.rom.version",
        "ro.harmony.version",
        "ro.build.version.emui",
        "ro.build.version.magic",
        "ro.build.version.hios",
        "ro.xos.version",
        "ro.tranzone.version",
        "ro.lenovo.zui_version"
    )

    private val XIAOMI_FIRMWARE_PROPERTIES = setOf(
        "ro.mi.os.version.name",
        "ro.miui.ui.version.name"
    )

    private val propertyCache = mutableMapOf<String, String?>()

    fun isOnRestrictedList(): Boolean {
        android.util.Log.d("XDBG", "[DeviceBrandChecker] isOnRestrictedList enter") //xdbg
        for (prop in RESTRICTED_FIRMWARE_PROPERTIES) {
            android.util.Log.d("XDBG", "[DeviceBrandChecker] loop") //xdbg
            val propValue = getSystemProperty(prop)
            if (!propValue.isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[DeviceBrandChecker] if branch") //xdbg
                android.util.Log.d("XDBG", "[DeviceBrandChecker] return") //xdbg
                return true
            }
        }
        android.util.Log.d("XDBG", "[DeviceBrandChecker] return") //xdbg
        return false
    }

    fun isXiaomiFirmware(): Boolean {
        android.util.Log.d("XDBG", "[DeviceBrandChecker] isXiaomiFirmware enter") //xdbg
        for (prop in XIAOMI_FIRMWARE_PROPERTIES) {
            android.util.Log.d("XDBG", "[DeviceBrandChecker] loop") //xdbg
            if (!getSystemProperty(prop).isNullOrEmpty()) {
                android.util.Log.d("XDBG", "[DeviceBrandChecker] if branch") //xdbg
                android.util.Log.d("XDBG", "[DeviceBrandChecker] return") //xdbg
                return true
            }
        }
        android.util.Log.d("XDBG", "[DeviceBrandChecker] return") //xdbg
        return false
    }

    private fun getSystemProperty(key: String): String? {
        android.util.Log.d("XDBG", "[DeviceBrandChecker] getSystemProperty enter key=${key}") //xdbg
        if (propertyCache.containsKey(key)) {
            android.util.Log.d("XDBG", "[DeviceBrandChecker] if branch") //xdbg
            android.util.Log.d("XDBG", "[DeviceBrandChecker] return") //xdbg
            return propertyCache[key]
        }

        android.util.Log.d("XDBG", "[DeviceBrandChecker] return") //xdbg
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod: Method = systemPropertiesClass.getMethod("get", String::class.java)
            val value = getMethod.invoke(null, key) as? String
            propertyCache[key] = value
            value
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[DeviceBrandChecker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            propertyCache[key] = null
            null
        }
    }
}
