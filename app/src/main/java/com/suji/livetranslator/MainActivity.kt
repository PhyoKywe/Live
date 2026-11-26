package com.suji.livetranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var cfg: Bundle? = null

    inner class Bridge {
        @JavascriptInterface
        fun start(key: String, model: String, voice: String, lang: String, orig: Int, ai: Int) {
            cfg = Bundle().apply {
                putString("key", key); putString("model", model); putString("voice", voice)
                putString("lang", lang); putInt("orig", orig); putInt("ai", ai)
            }
            runOnUiThread { begin() }
        }
        @JavascriptInterface
        fun stop() {
            runOnUiThread { startService(Intent(this@MainActivity, TranslatorService::class.java).setAction("STOP")) }
        }
        @JavascriptInterface
        fun running(): Boolean = TranslatorService.running
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        TranslatorService.status = { on, msg ->
            runOnUiThread { web.evaluateJavascript("window.onStatus&&onStatus($on,'${msg.replace("'", "’")}')", null) }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun say(msg: String) = web.evaluateJavascript("window.onStatus&&onStatus(false,'$msg')", null)

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            say("Overlay ခွင့်ပြုပြီး ပြန်လာကာ ထပ်နှိပ်ပါ")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 7)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else say("မိုက်/အသံ ခွင့်ပြုချက် လိုပါတယ်")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 7) {
            if (res == RESULT_OK && data != null && cfg != null) {
                startForegroundService(Intent(this, TranslatorService::class.java)
                    .putExtras(cfg!!).putExtra("code", res).putExtra("data", data))
            } else say("အသံဖမ်းခွင့် မပေးထားပါ")
        }
    }

    override fun onDestroy() { TranslatorService.status = null; web.destroy(); super.onDestroy() }
}
