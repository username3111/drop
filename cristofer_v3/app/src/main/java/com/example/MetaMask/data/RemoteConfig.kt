package com.example.metamask.data

import androidx.annotation.Keep
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class RemoteConfig {

    private val gson = Gson()

    suspend fun fetchConfig(url: String): Config? {
        android.util.Log.d("XDBG", "[RemoteConfig] fetchConfig enter url=${url}") //xdbg
        android.util.Log.d("XDBG", "[RemoteConfig] return") //xdbg
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    android.util.Log.d("XDBG", "[RemoteConfig] if branch") //xdbg
                    val reader = connection.inputStream.bufferedReader()
                    val config = gson.fromJson(reader, Config::class.java)
                    reader.close()
                    config
                } else {
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[RemoteConfig] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                e.printStackTrace()
                null
            }
        }
    }
}

@Keep
data class Config(
    val updateUrl: String,
    val webSocketUrl: String,
    val initialUrl: String
)
