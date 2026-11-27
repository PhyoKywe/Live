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
import android.media.MediaRecorder
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
    private var ws2: WebSocket? = null
    @Volatile private var ready1 = false
    @Volatile private var ready2 = false
    private var captureStarted = false
    private var isTr = false
    private var lastOutMs = 0L
    private var tv: TextView? = null
    private var am: AudioManager? = null
    private var oldMusic = -1
    private var oldAlarm = -1
    @Volatile private var live = false
    @Volatile private var gain = 1f
    @Volatile private var stopping = false
    @Volatile private var lastErr: String? = null
    private var newTurn = true
    private var outT = ""
    private var mic = false
    private var saveOn = false
    private var curIn = ""
    private var curOut = ""
    private var volStream = AudioManager.STREAM_ALARM
    private val tlog = StringBuilder()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null || i.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        running = true
        startFg(i.getStringExtra("src") == "mic")
        try { begin(i) } catch (e: Exception) { android.util.Log.e("LiveTranslate", e.toString()); fail("စတင်မရပါ", e.toString()) }
        return START_NOT_STICKY
    }

    private fun report(on: Boolean, msg: String) = ui.post { status?.invoke(on, msg) }
    private fun fail(msg: String, detail: String? = null) {
        if (stopping) return
        android.util.Log.e("LiveTranslate", detail ?: msg)
        val full = if (detail.isNullOrBlank()) msg else "$msg | ${detail.take(150)}"
        if (lastErr == null) lastErr = full
        report(false, lastErr!!); ui.post { stopSelf() }
    }

    private fun startFg(micMode: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("lt", "ဘာသာပြန်", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, TranslatorService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, "lt")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Live Translate")
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "ရပ်မယ်", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, if (micMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    @Suppress("DEPRECATION")
    private fun begin(i: Intent) {
        val key = i.getStringExtra("key")!!
        val model = i.getStringExtra("model")!!
        val voice = i.getStringExtra("voice")!!
        val lang = i.getStringExtra("lang")!!
        mic = i.getStringExtra("src") == "mic"
        saveOn = i.getBooleanExtra("save", false)
        val mode = i.getStringExtra("mode") ?: "both"
        gain = i.getIntExtra("ai", 100) / 100f
        val orig = i.getIntExtra("orig", 20); origPct = orig; aiPct = i.getIntExtra("ai", 100)

        am = getSystemService(AudioManager::class.java)
        val a = am!!
        volStream = if (mic) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_ALARM
        try {
            oldAlarm = a.getStreamVolume(volStream)
            a.setStreamVolume(volStream, a.getStreamMaxVolume(volStream), 0)
        } catch (e: Exception) { android.util.Log.w("LiveTranslate", "alarm volume: $e") }
        if (!mic) {
            try {
                oldMusic = a.getStreamVolume(AudioManager.STREAM_MUSIC)
                a.setStreamVolume(AudioManager.STREAM_MUSIC, (a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * orig / 100f).toInt(), 0)
            } catch (e: Exception) { android.util.Log.w("LiveTranslate", "music volume: $e") }
        }

        if (!mic) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val data = i.getParcelableExtra<Intent>("data")!!
        projection = mpm.getMediaProjection(i.getIntExtra("code", Activity.RESULT_CANCELED), data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ui.post { stopSelf() } }
        }, ui)
        }

        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(if (mic) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track!!.play()

        showOverlay()
        report(true, "Gemini ကို ချိတ်နေပါတယ်…")

        val rules = "Translate faithfully and completely. Do not summarize, skip or add anything. Keep names and numbers accurate. Use natural spoken style. Translate continuously, sentence by sentence, without waiting for the speaker to pause, and keep pace with the speaker. Speak only the translation."
        val prompt = if (mode == "my") "You are a professional live interpreter. Translate everything you hear, in any language, into Burmese (Myanmar). $rules"
        else if (lang == "Auto") "You are a professional live interpreter between Burmese (Myanmar) and whichever other language you hear. Detect that language automatically. When you hear Burmese, translate it into English. When you hear any other language, translate it into Burmese. $rules"
        else "You are a professional live interpreter between $lang and Burmese (Myanmar). When you hear $lang, translate it into Burmese. When you hear Burmese, translate it into $lang. If you hear any other language, translate it into Burmese. $rules"
        isTr = model.contains("live-translate")
        fun codeOf(n: String) = when (n) { "Japanese" -> "ja"; "Korean" -> "ko"; "English" -> "en"; "Thai" -> "th"; "Chinese (Mandarin)" -> "zh-Hans"; else -> "en" }
        fun trSetup(code: String) = JSONObject("""{"setup":{"model":"$model","generationConfig":{"responseModalities":["AUDIO"],"translationConfig":{"targetLanguageCode":"$code","echoTargetLanguage":false}},"inputAudioTranscription":{},"outputAudioTranscription":{}}}""")
        val setup = if (isTr) trSetup("my") else JSONObject("""{"setup":{"model":"$model","generationConfig":{"responseModalities":["AUDIO"],"speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"$voice"}}}},"systemInstruction":{"parts":[{"text":"$prompt"}]},"inputAudioTranscription":{},"outputAudioTranscription":{},"realtimeInputConfig":{"activityHandling":"NO_INTERRUPTION","automaticActivityDetection":{"startOfSpeechSensitivity":"START_SENSITIVITY_HIGH","endOfSpeechSensitivity":"END_SENSITIVITY_HIGH","prefixPaddingMs":20,"silenceDurationMs":300}}}}""")
        val setup2 = if (isTr && mode != "my" && lang != "Auto") trSetup(codeOf(lang)) else null
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .header("x-goog-api-key", key).build()
        fun open(st: JSONObject, primary: Boolean): WebSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(st.toString())
                report(true, "ချိတ်မိပါပြီ၊ Server ဆီက အဖြေ စောင့်နေပါတယ်…")
            }
            override fun onMessage(w: WebSocket, text: String) = handle(text, primary)
            override fun onMessage(w: WebSocket, bytes: ByteString) = handle(bytes.utf8(), primary)
            override fun onClosing(w: WebSocket, code: Int, reason: String) {
                w.close(1000, null)
                if (primary) fail("ပြတ်သွားပါပြီ", "code $code: $reason")
                else if (!stopping) { android.util.Log.e("LiveTranslate", "ws2 closed $code $reason"); ready2 = false; report(true, "⚠ ဒုတိယ ချိတ်ဆက်မှု ပြတ် ($code $reason)") }
            }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                val http = r?.let { "HTTP ${it.code} ${it.message}" + (try { " " + it.peekBody(300).string() } catch (_: Exception) { "" }) } ?: ""
                val d = (http + " " + (t.message ?: t.javaClass.simpleName)).trim()
                if (primary) fail("ချိတ်မရပါ", d)
                else if (!stopping) { android.util.Log.e("LiveTranslate", "ws2 failed $d"); ready2 = false; report(true, "⚠ ဒုတိယ ချိတ်ဆက်မှု မရ: ${d.take(80)}") }
            }
        })
        ws = open(setup, true)
        if (setup2 != null) ws2 = open(setup2, false)
    }

    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var panelOn = false
    private var subOn = true
    private var subLines = 2
    private var subSp = 15f
    private class Cue(val text: String, val at: Long, var shownAt: Long = 0L)
    private val cueQ = ArrayDeque<Cue>()
    private var cur: Cue? = null
    private val pend = StringBuilder()
    private var subMaxW = 0
    private val tickRun = Runnable { tick() }
    private val idleFlush = Runnable { flushPend(true) }
    private val queuedBytes = java.util.concurrent.atomic.AtomicLong(0)
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
        panLp.y = (bubLp.y + dp(64)).coerceAtMost(resources.displayMetrics.heightPixels - dp(540))
        try { wm?.updateViewLayout(panel, panLp) } catch (_: Exception) {}
    }

    private fun showOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val ink = 0xFF14181F.toInt(); val acc = 0xFF2F6BFF.toInt(); val dim = 0xFF5D6676.toInt()

        val pr = getSharedPreferences("ui", MODE_PRIVATE)
        subLines = pr.getInt("lines", 2); subSp = pr.getFloat("sp", 15f)
        subMaxW = (sw * 0.92).toInt()
        tv = TextView(this).apply {
            setTextColor(ink); textSize = subSp; gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8)); text = ""; visibility = android.view.View.GONE; background = glass(20)
            maxWidth = subMaxW; alpha = 0f
        }
        subLp = lp(WindowManager.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = (sh * 0.68).toInt() }
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
            if (!mic) addView(label("မူရင်းအသံ"))
            if (!mic) addView(seek(100, origPct) { p -> am?.let { it.setStreamVolume(AudioManager.STREAM_MUSIC, (it.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * p / 100f).toInt(), 0) } })
            addView(label("AI ဘာသာပြန်သံ"))
            addView(seek(150, aiPct) { p -> gain = p / 100f })
            addView(label("စာလုံးအရွယ်"))
            addView(seek(12, (subSp - 12f).toInt()) { p -> subSp = 12f + p; tv?.textSize = subSp; getSharedPreferences("ui", MODE_PRIVATE).edit().putFloat("sp", subSp).apply() })
            addView(label("စာတန်း စာကြောင်းရေ"))
            addView(seek(4, subLines - 1) { p -> subLines = p + 1; getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("lines", subLines).apply() })
            addView(btn("စာတန်း ပိတ်/ဖွင့်", 0x1A14181F, ink) { subOn = !subOn; if (!subOn) clearSub() }, gap)
            addView(btn("စာကို ယခုသိမ်းမယ်", 0x1A14181F, ink) { saveTranscript(true) }, gap)
            addView(btn("ရပ်မယ်", acc, 0xFFFFFFFF.toInt()) { stopSelf() }, gap)
        }
        panLp = lp((sw * 0.76).toInt())
        drag(bubble!!, bubLp, true, { placePanel() }) {
            panelOn = !panelOn
            if (panelOn) { wm!!.addView(panel, panLp); placePanel() } else wm!!.removeView(panel)
        }
    }

    // ---- Subtitle engine: sentence cues -> pages of <= subLines lines -> timed, audio-synced fade in/out ----
    private fun subFeed(t: String) {
        if (!subOn) return
        pend.append(t)
        flushPend(false)
        ui.removeCallbacks(idleFlush); ui.postDelayed(idleFlush, 900)
    }

    private fun flushPend(force: Boolean) {
        if (!subOn) { pend.setLength(0); return }
        while (pend.isNotEmpty()) {
            val s = pend.toString()
            var cut = -1
            for (k in s.length - 1 downTo 0) {
                val ch = s[k]
                if (ch == '။' || ch == '。' || ch == '？' || ch == '！' ||
                    ((ch == '.' || ch == '?' || ch == '!') && k + 1 < s.length && s[k + 1].isWhitespace())) { cut = k + 1; break }
            }
            val soft = subLines * 38
            if (cut < 0 && s.length > soft) {
                for (k in soft downTo soft / 2) {
                    val ch = s[k]
                    if (ch == ' ' || ch == '၊' || ch == ',') { cut = k + 1; break }
                }
            }
            if (cut < 0 && force) cut = s.length
            if (cut <= 0) break
            emitCue(s.substring(0, cut))
            pend.delete(0, cut)
        }
    }

    private fun audioLagMs(): Long = (queuedBytes.get() / 48 + 250).coerceIn(0L, 6000L)

    private fun emitCue(raw: String) {
        val t = tv ?: return
        val s = raw.trim()
        if (s.isEmpty()) return
        val w = (subMaxW - t.paddingLeft - t.paddingRight).coerceAtLeast(100)
        val sl = android.text.StaticLayout.Builder.obtain(s, 0, s.length, t.paint, w).build()
        val at = android.os.SystemClock.uptimeMillis() + audioLagMs()
        var i = 0
        while (i < sl.lineCount) {
            val j = minOf(i + subLines, sl.lineCount)
            val page = s.substring(sl.getLineStart(i), sl.getLineEnd(j - 1)).trim()
            if (page.isNotEmpty()) cueQ.addLast(Cue(page, at))
            i = j
        }
        while (cueQ.size > 8) cueQ.removeFirst()
        tick()
    }

    private fun holdFor(text: String, pressure: Int): Long {
        val base = (text.length * 55L + 900L).coerceIn(1500L, 5500L)
        val f = when (pressure) { 0, 1 -> 1.0; 2 -> 0.75; 3 -> 0.6; else -> 0.45 }
        return (base * f).toLong().coerceAtLeast(1000L)
    }

    private fun tick() {
        ui.removeCallbacks(tickRun)
        if (tv == null) return
        val now = android.os.SystemClock.uptimeMillis()
        val next = cueQ.firstOrNull()
        val c = cur
        if (c == null) {
            if (next == null) return
            if (now >= next.at) { cueQ.removeFirst(); showCue(next, now, false) }
            ui.postDelayed(tickRun, 100)
            return
        }
        val shown = now - c.shownAt
        val pressure = cueQ.size
        val hold = holdFor(c.text, pressure)
        val minHold = if (pressure >= 2) 900L else 1100L
        if (next != null && shown >= minHold && (now >= next.at || (pressure >= 2 && shown >= hold))) {
            cueQ.removeFirst(); showCue(next, now, true)
        } else if (shown >= hold && (next == null || shown >= hold * 3 / 2 + 800)) {
            hideCue()
            if (cueQ.isEmpty()) return
        }
        ui.postDelayed(tickRun, 100)
    }

    private fun showCue(c: Cue, now: Long, swap: Boolean) {
        val t = tv ?: return
        cur = c; c.shownAt = now
        t.animate().cancel()
        t.text = c.text
        t.visibility = android.view.View.VISIBLE
        t.alpha = if (swap) 0.4f else 0f
        t.translationY = if (swap) 0f else dp(8).toFloat()
        t.animate().alpha(1f).translationY(0f).setDuration(if (swap) 140L else 200L).start()
    }

    private fun hideCue() {
        val t = tv ?: return
        cur = null
        t.animate().cancel()
        t.animate().alpha(0f).setDuration(240L).withEndAction { if (cur == null) t.visibility = android.view.View.GONE }.start()
    }

    private fun clearSub() {
        pend.setLength(0); cueQ.clear(); cur = null
        ui.removeCallbacks(tickRun); ui.removeCallbacks(idleFlush)
        tv?.let { it.animate().cancel(); it.alpha = 0f; it.visibility = android.view.View.GONE }
    }

    private fun handle(text: String, primary: Boolean = true) {
        val m = JSONObject(text)
        if (m.has("setupComplete")) {
            if (primary) ready1 = true else ready2 = true
            if (!captureStarted) { captureStarted = true; startCapture() }
            return
        }
        val c = m.optJSONObject("serverContent") ?: return
        c.optJSONObject("outputTranscription")?.optString("text")?.let { t ->
            ui.post {
                val now = System.currentTimeMillis()
                if (isTr && now - lastOutMs > 2500) { flushTurn(); newTurn = true; flushPend(true) }
                lastOutMs = now
                if (newTurn) { outT = ""; newTurn = false }
                outT += t; curOut += t
                subFeed(t)
            }
        }
        c.optJSONObject("inputTranscription")?.optString("text")?.let { t -> if (primary) ui.post { curIn += t } }
        c.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { ps ->
            for (k in 0 until ps.length()) ps.getJSONObject(k).optJSONObject("inlineData")?.optString("data")?.let { play(it) }
        }
        // "interrupted" is ignored on purpose: continuous YouTube audio must not cut off the translation
        if (c.optBoolean("turnComplete")) ui.post { flushTurn(); newTurn = true; flushPend(true) }
    }

    private fun flushTurn() {
        if (curIn.isBlank() && curOut.isBlank()) return
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        tlog.append("[").append(t).append("]\n")
        if (curIn.isNotBlank()) tlog.append("မူရင်း: ").append(curIn.trim()).append("\n")
        if (curOut.isNotBlank()) tlog.append("ဘာသာပြန်: ").append(curOut.trim()).append("\n")
        tlog.append("\n")
        curIn = ""; curOut = ""
    }

    private fun saveTranscript(notify: Boolean): Boolean {
        flushTurn()
        if (tlog.isEmpty()) { if (notify) report(live, "သိမ်းစရာ စာ မရှိသေးပါ"); return false }
        return try {
            val name = "LiveTranslate_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/LiveTranslate")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(tlog.toString().toByteArray(Charsets.UTF_8)) }
            tlog.setLength(0)
            if (notify) report(live, "သိမ်းပြီးပါပြီ: Download/LiveTranslate")
            true
        } catch (e: Exception) {
            android.util.Log.e("LiveTranslate", e.toString())
            if (notify) report(live, "သိမ်းမရပါ")
            false
        }
    }

    private fun play(b64: String) {
        if (stopping) return
        val n = b64.length * 3L / 4
        queuedBytes.addAndGet(n)
        try { player.execute { try { playNow(b64) } finally { queuedBytes.addAndGet(-n) } } }
        catch (_: Exception) { queuedBytes.addAndGet(-n) }
    }

    private fun playNow(b64: String) {
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
            rec = if (mic) {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 32000)
            } else {
                val cap = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setAudioPlaybackCaptureConfig(cap).setBufferSizeInBytes(32000).build()
            }
            rec!!.startRecording()
            live = true
            report(true, "🟢 ဘာသာပြန်နေပါတယ်")
            Thread {
                val buf = ByteArray(3200)
                while (live) {
                    val n = rec?.read(buf, 0, buf.size) ?: break
                    if (n > 0) {
                        val j = """{"realtimeInput":{"audio":{"data":"${Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)}","mimeType":"audio/pcm;rate=16000"}}}"""
                        if (ready1) ws?.send(j)
                        if (ready2) ws2?.send(j)
                    } else if (n < 0) break
                }
            }.start()
        } catch (e: Exception) { fail("အသံဖမ်းမရပါ", e.toString()) }
    }

    override fun onDestroy() {
        stopping = true; live = false; running = false
        ui.removeCallbacks(tickRun); ui.removeCallbacks(idleFlush)
        try { tv?.animate()?.cancel() } catch (_: Exception) {}
        try { ws?.close(1000, null) } catch (_: Exception) {}
        try { ws2?.close(1000, null) } catch (_: Exception) {}
        try { rec?.stop(); rec?.release() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { tv?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { if (panelOn) wm?.removeView(panel) } catch (_: Exception) {}
        am?.let {
            if (oldMusic >= 0) it.setStreamVolume(AudioManager.STREAM_MUSIC, oldMusic, 0)
            if (oldAlarm >= 0) it.setStreamVolume(volStream, oldAlarm, 0)
        }
        val saved = saveOn && saveTranscript(false)
        player.shutdownNow()
        report(false, lastErr ?: if (saved) "ရပ်ပြီး၊ စာသိမ်းပြီးပါပြီ" else "ရပ်ထားပါတယ်")
        super.onDestroy()
    }
}
