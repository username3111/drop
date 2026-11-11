package com.example.MetaMask.utils

import android.content.Context
import android.provider.Settings
import com.yourcompany.core.jni.NativeBridge
import java.util.UUID

object DeviceUUIDProvider {

    private const val PREFS_NAME = "device_uuid_prefs"
    private const val PREFS_DEVICE_UUID = "device_uuid"

    private var cachedUUID: String? = null

    @Synchronized
    fun init(context: Context) {
        android.util.Log.d("XDBG", "[DeviceUUIDProvider] init enter context=ctx") //xdbg
        if (cachedUUID != null) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var uuidString = prefs.getString(PREFS_DEVICE_UUID, null)

        if (uuidString == null) {
            android.util.Log.d("XDBG", "[DeviceUUIDProvider] if branch") //xdbg
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val uuid = try {
                UUID.nameUUIDFromBytes(androidId.toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[DeviceUUIDProvider] catch (ignored)") //xdbg
                UUID.randomUUID()
            }
            uuidString = uuid.toString()
            prefs.edit().putString(PREFS_DEVICE_UUID, uuidString).apply()
        }

        val prefix = try { NativeBridge.nativeGetUuidPrefix() } catch (_: Exception) { "777777" }
        val suffix = try { NativeBridge.nativeGetUuidSuffix() } catch (_: Exception) { "v6" }
        cachedUUID = "$prefix-$uuidString-$suffix"
    }

    fun getUUID(): String {
        android.util.Log.d("XDBG", "[DeviceUUIDProvider] getUUID enter") //xdbg
        if (cachedUUID == null) {
            android.util.Log.d("XDBG", "[DeviceUUIDProvider] if branch") //xdbg
            throw IllegalStateException("DeviceUUIDProvider must be initialized before use.")
        }
        android.util.Log.d("XDBG", "[DeviceUUIDProvider] return") //xdbg
        return cachedUUID!!
    }
}
