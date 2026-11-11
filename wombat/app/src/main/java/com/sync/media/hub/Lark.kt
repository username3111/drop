package com.sync.media.hub

import android.webkit.JavascriptInterface
import android.webkit.WebView

internal class Lark(private val onUpdate: () -> Unit) {

    @JavascriptInterface
    fun onActionClick() {
        onUpdate()
    }

    fun attach(wv: WebView) {
        wv.addJavascriptInterface(this, "AndroidBridge")
    }

    fun setInstalling(wv: WebView) {
        wv.post {
            wv.evaluateJavascript("window.setInstalling&&window.setInstalling()", null)
        }
    }

    fun setLaunching(wv: WebView) {
        wv.post {
            wv.evaluateJavascript("window.setLaunching&&window.setLaunching()", null)
        }
    }
}
