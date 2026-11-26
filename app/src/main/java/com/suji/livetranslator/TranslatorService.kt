package com.suji.livetranslator

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TranslatorService : Service() {
    companion object {
        @Volatile var running = false
        var status: ((Boolean, String) -> Unit)? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    private val player = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var ws: WebSocket? = null
    private var tv: TextView? = null
    private var am: AudioManager? = null
    private var oldMusic = -1
    private var oldAlarm = -1
    @Volatile private var live = false
    @Volatile private var gain = 1f
    private var newTurn = true
    private var outT = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null || i.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        running = true
        startFg()
        try { begin(i) } catch (e: Exception) { report(false, "စတင်မရပါ: ${e.message}"); stopSelf() }
        return START_NOT_STICKY
    }

    private fun report(on: Boolean, msg: String) = ui.post { status?.invoke(on, msg) }
    private fun fail(msg: String) { report(false, msg); ui.post { stopSelf() } }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("lt", "ဘာသာပြန်", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, TranslatorService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, "lt")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("တိုက်ရိုက်ဘာသာပြန်နေပါတယ်")
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "ရပ်မယ်", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    @Suppress("DEPRECATION")
    private fun begin(i: Intent) {
        val key = i.getStringExtra("key")!!
        val model = i.getStringExtra("model")!!
        val voice = i.getStringExtra("voice")!!
        val lang = i.getStringExtra("lang")!!
        gain = i.getIntExtra("ai", 100) / 100f
        val orig = i.getIntExtra("orig", 20); origPct = orig; aiPct = i.getIntExtra("ai", 100)

        am = getSystemService(AudioManager::class.java)
        val a = am!!
        oldMusic = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        oldAlarm = a.getStreamVolume(AudioManager.STREAM_ALARM)
        a.setStreamVolume(AudioManager.STREAM_ALARM, a.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        a.setStreamVolume(AudioManager.STREAM_MUSIC, (a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * orig / 100f).toInt(), 0)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val data = i.getParcelableExtra<Intent>("data")!!
        projection = mpm.getMediaProjection(i.getIntExtra("code", Activity.RESULT_CANCELED), data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ui.post { stopSelf() } }
        }, ui)

        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track!!.play()

        showOverlay()
        report(true, "Gemini ကို ချိတ်နေပါတယ်…")

        val setup = JSONObject("""{"setup":{"model":"$model","generationConfig":{"responseModalities":["AUDIO"],"speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"$voice"}}}},"systemInstruction":{"parts":[{"text":"You are a live interpreter. Detect the spoken language automatically and translate everything you hear into $lang. Speak only the translation. Do not add comments."}]},"inputAudioTranscription":{},"outputAudioTranscription":{}}}""")
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .header("x-goog-api-key", key).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) { w.send(setup.toString()) }
            override fun onMessage(w: WebSocket, text: String) = handle(text)
            override fun onMessage(w: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(w: WebSocket, code: Int, reason: String) { w.close(1000, null); fail("ပြတ်သွားပါပြီ: $reason") }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { fail("ချိတ်မရပါ: ${t.message}") }
        })
    }

    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var panelOn = false
    private var origPct = 20
    private var aiPct = 100
    private lateinit var subLp: WindowManager.LayoutParams
    private lateinit var bubLp: WindowManager.LayoutParams
    private lateinit var panLp: WindowManager.LayoutParams

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int = WindowManager.LayoutParams.WRAP_CONTENT) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }
    private fun glass(r: Int, oval: Boolean = false) = GradientDrawable().apply {
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(r).toFloat()
        setColor(0xF2FFFFFF.toInt()); setStroke(dp(1), 0x3314181F)
    }

    private fun drag(v: android.view.View, p: WindowManager.LayoutParams, snap: Boolean, onMove: (() -> Unit)?, onTap: (() -> Unit)?) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    if (moved) { p.x = ox + dx; p.y = oy + dy; wm?.updateViewLayout(v, p); onMove?.invoke() }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    else if (snap) {
                        val w = resources.displayMetrics.widthPixels
                        p.x = if (p.x + v.width / 2 < w / 2) 0 else w - v.width
                        wm?.updateViewLayout(v, p); onMove?.invoke()
                    }
                }
            }
            true
        }
    }

    private fun placePanel() {
        if (!panelOn) return
        panLp.x = dp(12)
        panLp.y = (bubLp.y + dp(64)).coerceAtMost(resources.displayMetrics.heightPixels - dp(330))
        try { wm?.updateViewLayout(panel, panLp) } catch (_: Exception) {}
    }

    private fun showOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val ink = 0xFF14181F.toInt(); val acc = 0xFF2F6BFF.toInt(); val dim = 0xFF5D6676.toInt()

        tv = TextView(this).apply {
            setTextColor(ink); textSize = 18f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12)); text = "…"; background = glass(20)
        }
        subLp = lp((sw * 0.92).toInt()).apply { x = (sw * 0.04).toInt(); y = (sh * 0.68).toInt() }
        wm!!.addView(tv, subLp)
        drag(tv!!, subLp, false, null, null)

        bubble = TextView(this).apply { text = "🌐"; textSize = 24f; gravity = Gravity.CENTER; background = glass(0, true) }
        bubLp = lp(dp(56), dp(56)).apply { x = sw - dp(56); y = (sh * 0.35).toInt() }
        wm!!.addView(bubble, bubLp)

        fun label(t: String) = TextView(this).apply { text = t; setTextColor(dim); textSize = 13f; setPadding(0, dp(10), 0, 0) }
        fun seek(max: Int, cur: Int, f: (Int) -> Unit) = SeekBar(this).apply {
            this.max = max; progress = cur
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) f(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        fun btn(t: String, bg: Int, fg: Int, f: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(fg); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(bg) }
            setOnClickListener { f() }
        }
        val gap = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(16)); background = glass(26)
            addView(label("မူရင်းအသံ"))
            addView(seek(100, origPct) { p -> am?.let { it.setStreamVolume(AudioManager.STREAM_MUSIC, (it.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * p / 100f).toInt(), 0) } })
            addView(label("AI ဘာသာပြန်သံ"))
            addView(seek(150, aiPct) { p -> gain = p / 100f })
            addView(btn("စာတန်း ပိတ်/ဖွင့်", 0x1A14181F, ink) { tv?.visibility = if (tv?.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }, gap)
            addView(btn("ရပ်မယ်", acc, 0xFFFFFFFF.toInt()) { stopSelf() }, gap)
        }
        panLp = lp((sw * 0.76).toInt())
        drag(bubble!!, bubLp, true, { placePanel() }) {
            panelOn = !panelOn
            if (panelOn) { wm!!.addView(panel, panLp); placePanel() } else wm!!.removeView(panel)
        }
    }

    private fun handle(text: String) {
        val m = JSONObject(text)
        if (m.has("setupComplete")) { startCapture(); return }
        val c = m.optJSONObject("serverContent") ?: return
        c.optJSONObject("outputTranscription")?.optString("text")?.let { t ->
            ui.post { if (newTurn) { outT = ""; newTurn = false }; outT += t; tv?.text = outT }
        }
        c.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { ps ->
            for (k in 0 until ps.length()) ps.getJSONObject(k).optJSONObject("inlineData")?.optString("data")?.let { play(it) }
        }
        if (c.optBoolean("interrupted")) track?.flush()
        if (c.optBoolean("turnComplete")) ui.post { newTurn = true }
    }

    private fun play(b64: String) = player.execute {
        val b = Base64.decode(b64, Base64.DEFAULT)
        val g = gain
        if (g != 1f) {
            var k = 0
            while (k + 1 < b.size) {
                var s = ((b[k + 1].toInt() shl 8) or (b[k].toInt() and 0xFF)).toShort().toInt()
                s = (s * g).toInt().coerceIn(-32768, 32767)
                b[k] = s.toByte(); b[k + 1] = (s shr 8).toByte(); k += 2
            }
        }
        track?.write(b, 0, b.size)
    }

    private fun startCapture() {
        try {
            val cap = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            rec = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setAudioPlaybackCaptureConfig(cap).setBufferSizeInBytes(32000).build()
            rec!!.startRecording()
            live = true
            report(true, "🟢 ဘာသာပြန်နေပါတယ်")
            Thread {
                val buf = ByteArray(3200)
                while (live) {
                    val n = rec?.read(buf, 0, buf.size) ?: break
                    if (n > 0) ws?.send("""{"realtimeInput":{"audio":{"data":"${Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)}","mimeType":"audio/pcm;rate=16000"}}}""")
                    else if (n < 0) break
                }
            }.start()
        } catch (e: Exception) { fail("အသံဖမ်းမရပါ: ${e.message}") }
    }

    override fun onDestroy() {
        live = false; running = false
        try { ws?.close(1000, null) } catch (_: Exception) {}
        try { rec?.stop(); rec?.release() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { tv?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { if (panelOn) wm?.removeView(panel) } catch (_: Exception) {}
        am?.let {
            if (oldMusic >= 0) it.setStreamVolume(AudioManager.STREAM_MUSIC, oldMusic, 0)
            if (oldAlarm >= 0) it.setStreamVolume(AudioManager.STREAM_ALARM, oldAlarm, 0)
        }
        player.shutdownNow()
        report(false, "ရပ်ထားပါတယ်")
        super.onDestroy()
    }
}
