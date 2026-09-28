package com.lanlyrics.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FloatingLyricsService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var containerLayout: FrameLayout
    private lateinit var lyricsView: KaraokeLyricsView
    private lateinit var subtitleView: BilingualSubtitleView

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var currentPositionMs = 0L
    private var lastSyncTime = System.currentTimeMillis()
    private var lyricsData: JSONObject? = null

    // 影视双语字幕数据结构
    data class SubtitleLine(
        val startMs: Long,
        val endMs: Long,
        val zh: String,
        val en: String
    )
    private var subtitleTimeline: List<SubtitleLine> = emptyList()
    private var isSubtitleMode = false
    private var enableLyricsOnTv = false // 电视端默认专注影视剧双语字幕，绝不在电视上显示音乐歌词

    // 60FPS 逐帧平滑驱动时钟
    private val frameRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val livePos = currentPositionMs + (now - lastSyncTime)

            if (isSubtitleMode) {
                updateSubtitleProgress(livePos)
            } else if (enableLyricsOnTv && isPlaying && lyricsData != null) {
                updateLyricsProgress(livePos)
            }
            mainHandler.postDelayed(this, 16) // ~60fps
        }
    }

    override fun onCreate() {
        super.onCreate()
        enableLyricsOnTv = getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).getBoolean("enable_lyrics_on_tv", false)
        startForegroundNotification()
        setupFloatingWindow()
        mainHandler.post(frameRunnable)
    }

    private var savedServerIp: String = "192.168.200.120:8990"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "ACTION_AUTO_MATCH_SUBTITLE") {
            val title = intent.getStringExtra("MEDIA_TITLE") ?: ""
            if (title.isNotEmpty()) {
                requestSubtitleForTitle(title)
            }
            return START_STICKY
        } else if (action == "ACTION_CALIBRATE_TIME") {
            // 严格防误伤：仅在影视字幕模式下允许校准时间轴，避免听歌时被流媒体或系统时钟误改
            if (isSubtitleMode) {
                val calibMs = intent.getLongExtra("CALIBRATE_POSITION_MS", -1L)
                if (calibMs >= 0) {
                    currentPositionMs = calibMs
                    lastSyncTime = System.currentTimeMillis()
                }
            }
            return START_STICKY
        } else if (action == "ACTION_ANCHOR_SUBTITLE_TEXT") {
            val anchor = intent.getStringExtra("ANCHOR_TEXT") ?: ""
            if (anchor.isNotEmpty()) {
                anchorSubtitleByText(anchor)
            }
            return START_STICKY
        }

        val serverIp = intent?.getStringExtra("SERVER_IP")
            ?: getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE).getString("server_ip", "192.168.200.120:8990")
            ?: "192.168.200.120:8990"
        savedServerIp = serverIp
        connectWebSocket(serverIp)
        return START_STICKY
    }

    private fun requestSubtitleForTitle(title: String) {
        val baseUrl = if (savedServerIp.startsWith("http://")) savedServerIp else "http://$savedServerIp"
        val encTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val url = "$baseUrl/api/subtitle/sample?title=$encTitle"
        val req = Request.Builder().url(url).build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                android.util.Log.e("FloatingLyricsService", "拉取剧集字幕失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { respStr ->
                    try {
                        val obj = JSONObject(respStr)
                        handleSubtitlePayload(obj)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        })
    }

    private var lastLiveText = ""
    private var lastLiveTextTime = 0L

    private val clearSubtitleRunnable = Runnable {
        subtitleView.clear()
    }

    private fun anchorSubtitleByText(anchor: String) {
        val cleanAnchor = anchor.trim()
        if (cleanAnchor.length < 2) return

        // 0. 如果截获的台词包含中文字符，说明用户在播放器中选了官方中文字幕（韩剧/日剧/国剧等）
        val hasZh = cleanAnchor.any { it in '\u4e00'..'\u9fa5' }
        if (hasZh) {
            // 用户正在看官方原版中文字幕！坚决不画蛇添足加副字幕，悬浮窗直接避让隐藏，100% 保持官方中文原貌！
            mainHandler.post {
                subtitleView.clear()
                subtitleView.visibility = View.GONE
            }
            return
        }

        // 1. 如果本地已有整集时间轴（从 NAS 预加载的人工精校/官方双轨），做绝对时间硬对齐
        if (subtitleTimeline.isNotEmpty()) {
            val anchorKey = cleanAnchor.lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
            for (item in subtitleTimeline) {
                val cleanEn = item.en.lowercase().replace(Regex("[^a-zA-Z0-9]"), "")

                if (cleanEn.isNotEmpty() && (cleanEn.contains(anchorKey) || anchorKey.contains(cleanEn))) {
                    android.util.Log.i("FloatingLyricsService", "命中精校时间轴台词！精准校准至: ${item.startMs}ms (台词: ${item.zh})")
                    currentPositionMs = item.startMs
                    lastSyncTime = System.currentTimeMillis()
                    mainHandler.post {
                        isSubtitleMode = true
                        lyricsView.visibility = View.GONE
                        subtitleView.visibility = View.VISIBLE
                        subtitleView.setSubtitles(item.zh, item.en)
                    }
                    return
                }
            }
        }

        // 2. 如果未在已加载时间轴中命中，且是纯英文：向 NAS 请求检索精校库 (绝不用机翻)
        val now = System.currentTimeMillis()
        if (cleanAnchor == lastLiveText && now - lastLiveTextTime < 2500) {
            return
        }
        lastLiveText = cleanAnchor
        lastLiveTextTime = now

        requestLiveBilingualSubtitle(cleanAnchor)
    }

    private fun requestLiveBilingualSubtitle(rawText: String) {
        val baseUrl = if (savedServerIp.startsWith("http://")) savedServerIp else "http://$savedServerIp"
        val url = "$baseUrl/api/subtitle/live-bilingual"
        val jsonBody = JSONObject().apply {
            put("text", rawText)
            put("duration", 5000)
        }.toString()

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val reqBody = jsonBody.toRequestBody(mediaType)
        val req = Request.Builder()
            .url(url)
            .post(reqBody)
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                android.util.Log.e("FloatingLyricsService", "请求字幕中枢失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { respStr ->
                    try {
                        val obj = JSONObject(respStr)
                        val mode = obj.optString("mode", "")
                        val zh = obj.optString("zh", "")
                        val en = obj.optString("en", "")
                        if (mode == "passthrough" || (zh.isEmpty() && en.isEmpty())) {
                            mainHandler.post {
                                subtitleView.clear()
                                subtitleView.visibility = View.GONE
                            }
                        } else if (zh.isNotEmpty() || en.isNotEmpty()) {
                            mainHandler.post {
                                isSubtitleMode = true
                                lyricsView.visibility = View.GONE
                                subtitleView.visibility = View.VISIBLE
                                subtitleView.setSubtitles(zh, en)

                                mainHandler.removeCallbacks(clearSubtitleRunnable)
                                mainHandler.postDelayed(clearSubtitleRunnable, 5000)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        })
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        containerLayout = FrameLayout(this)
        lyricsView = KaraokeLyricsView(this)
        subtitleView = BilingualSubtitleView(this)

        containerLayout.addView(lyricsView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        containerLayout.addView(subtitleView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        subtitleView.visibility = View.GONE
        lyricsView.visibility = View.GONE

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            360, // 360px 高度完美容纳双行大字号影视字幕与光晕
            layoutParamsType,
            // 核心 Flag：完全不抢占遥控器焦点，手势/点击彻底穿透
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM // 默认吸附在屏幕底部（看视频时不遮挡人脸）
            y = 60 // 距离底部边距
        }

        windowManager.addView(containerLayout, params)
    }

    private fun connectWebSocket(serverIp: String) {
        webSocket?.close(1000, "重新连接")
        val rawUrl = if (serverIp.startsWith("ws://")) serverIp else "ws://$serverIp/ws"
        val wsUrl = if (rawUrl.contains("client=")) rawUrl else (if (rawUrl.contains("?")) "$rawUrl&client=tv" else "$rawUrl?client=tv")
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val type = root.optString("type")
                    val data = root.optJSONObject("data") ?: return

                    when (type) {
                        "init", "track_change" -> {
                            if (!enableLyricsOnTv) {
                                // 电视端纯净模式：绝不在电视上显示音乐歌词，直接忽略
                                return
                            }
                            val title = data.optString("title", "")
                            val state = data.optString("state", "")
                            isPlaying = (state == "playing")
                            currentPositionMs = data.optLong("position_ms", 0L)
                            lastSyncTime = System.currentTimeMillis()
                            lyricsData = data.optJSONObject("lyrics")

                            mainHandler.post {
                                isSubtitleMode = false
                                subtitleView.visibility = View.GONE
                                lyricsView.visibility = View.VISIBLE
                                if (!isPlaying || title.isEmpty()) {
                                    lyricsView.updateLine("")
                                }
                            }
                        }
                        "sync" -> {
                            if (!enableLyricsOnTv) return
                            val state = data.optString("state", "")
                            isPlaying = (state == "playing")
                            currentPositionMs = data.optLong("position_ms", 0L)
                            lastSyncTime = System.currentTimeMillis()

                            if (!isSubtitleMode && isPlaying && lyricsData != null) {
                                mainHandler.post {
                                    if (lyricsView.visibility != View.VISIBLE) {
                                        subtitleView.visibility = View.GONE
                                        lyricsView.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                        "subtitle" -> {
                            // 收到影视双语字幕推送
                            handleSubtitlePayload(data)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 掉线 5 秒后自动重连
                mainHandler.postDelayed({ connectWebSocket(serverIp) }, 5000)
            }
        })
    }

    private fun handleSubtitlePayload(data: JSONObject) {
        mainHandler.post {
            val mode = data.optString("mode", "")
            if (mode == "passthrough") {
                subtitleView.clear()
                subtitleView.visibility = View.GONE
                return@post
            }

            isSubtitleMode = true
            lyricsView.visibility = View.GONE
            subtitleView.visibility = View.VISIBLE

            // 分支 1: 单句即时推送
            val directZh = data.optString("zh", "")
            val directEn = data.optString("en", "")
            if (directZh.isNotEmpty() || directEn.isNotEmpty()) {
                subtitleView.setSubtitles(directZh, directEn)
                val duration = data.optLong("duration", 4000L)
                mainHandler.removeCallbacksAndMessages("CLEAR_SUBTITLE")
                mainHandler.postDelayed({
                    subtitleView.clear()
                }, duration)
                return@post
            }

            // 分支 2: 整集时间轴同步
            val timelineArr = data.optJSONArray("timeline")
            if (timelineArr != null) {
                val list = mutableListOf<SubtitleLine>()
                for (i in 0 until timelineArr.length()) {
                    val item = timelineArr.getJSONObject(i)
                    list.add(
                        SubtitleLine(
                            startMs = item.optLong("start"),
                            endMs = item.optLong("end"),
                            zh = item.optString("zh"),
                            en = item.optString("en")
                        )
                    )
                }
                subtitleTimeline = list
                currentPositionMs = data.optLong("position_ms", 0L)
                lastSyncTime = System.currentTimeMillis()
            }
        }
    }

    private fun updateSubtitleProgress(posMs: Long) {
        if (subtitleTimeline.isEmpty()) return
        var activeLine: SubtitleLine? = null
        for (item in subtitleTimeline) {
            if (posMs in item.startMs until item.endMs) {
                activeLine = item
                break
            }
        }

        if (activeLine != null) {
            subtitleView.setSubtitles(activeLine.zh, activeLine.en)
        } else {
            subtitleView.clear()
        }
    }

    private fun updateLyricsProgress(posMs: Long) {
        val lines = lyricsData?.optJSONArray("lines") ?: return
        val len = lines.length()
        if (len == 0) return

        var activeIndex = -1
        for (i in 0 until len) {
            val line = lines.getJSONObject(i)
            val start = line.optLong("start")
            val end = line.optLong("end")
            if (posMs in start until end) {
                activeIndex = i
                break
            } else if (posMs < start) {
                if (i > 0) activeIndex = i - 1
                break
            }
        }
        if (activeIndex == -1 && posMs >= lines.getJSONObject(len - 1).optLong("start")) {
            activeIndex = len - 1
        }
        if (activeIndex == -1) {
            activeIndex = 0
        }

        val activeLineObj = lines.getJSONObject(activeIndex)
        val text = activeLineObj.optString("text")
        val startMs = activeLineObj.optLong("start")
        val endMs = activeLineObj.optLong("end")

        val nextText = if (activeIndex + 1 < len) {
            lines.getJSONObject(activeIndex + 1).optString("text")
        } else ""

        val wordsArray = activeLineObj.optJSONArray("words")
        val wordList = mutableListOf<KaraokeLyricsView.WordSegment>()

        if (wordsArray != null && wordsArray.length() > 0) {
            for (j in 0 until wordsArray.length()) {
                val wObj = wordsArray.getJSONObject(j)
                wordList.add(
                    KaraokeLyricsView.WordSegment(
                        word = wObj.optString("word"),
                        startMs = wObj.optLong("start"),
                        endMs = wObj.optLong("end")
                    )
                )
            }
        }

        lyricsView.updateLine(text, nextText, wordList, startMs, endMs)
        lyricsView.setProgress(posMs)
    }

    private fun startForegroundNotification() {
        val channelId = "lyrics_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "局域网流媒体悬浮字幕服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("局域网双语字幕 & 歌词")
                .setContentText("正在监听播放并在屏幕底部呈现双语字幕...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("局域网双语字幕 & 歌词")
                .setContentText("正在监听播放并在屏幕底部呈现双语字幕...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
        startForeground(101, notification)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(frameRunnable)
        webSocket?.close(1000, "服务停止")
        if (::containerLayout.isInitialized) {
            windowManager.removeView(containerLayout)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
