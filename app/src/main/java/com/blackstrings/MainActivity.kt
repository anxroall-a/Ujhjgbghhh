package com.blackstrings

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var torChatManager: TorChatManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }

        torChatManager = TorChatManager(
            context = this,
            callback = object : TorChatManager.Callback {
                override fun onTorConnected(myOnion: String) {
                    sendToHtml("onTorConnected(${JSONObject.quote(myOnion)})")
                }

                override fun onPeerConnected(onion: String) {
                    sendToHtml("onPeerConnected(${JSONObject.quote(onion)})")
                }

                override fun onMessageReceived(text: String) {
                    sendToHtml("onMessageReceived(${JSONObject.quote(text)})")
                }

                override fun onMessageSent(text: String) {
                    sendToHtml("onMessageSent(${JSONObject.quote(text)})")
                }

                override fun onError(err: String) {
                    sendToHtml("onError(${JSONObject.quote(err)})")
                }
            }
        )

        webView.addJavascriptInterface(WebAppInterface(), "Android")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun sendToHtml(jsCall: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript(jsCall, null)
            }
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun startTor() {
            torChatManager.startTor()
        }

        @JavascriptInterface
        fun connectToPeer(onion: String) {
            torChatManager.connectToPeer(onion)
        }

        @JavascriptInterface
        fun sendMessage(msg: String) {
            torChatManager.sendMessage(msg)
        }
    }

    override fun onDestroy() {
        torChatManager.stop()
        webView.destroy()
        super.onDestroy()
    }
}
