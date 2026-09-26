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

    // 60FPS 逐帧平滑驱动时钟
    private val frameRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val livePos = currentPositionMs + (now - lastSyncTime)

            if (isSubtitleMode) {
                updateSubtitleProgress(livePos)
            } else if (isPlaying && lyricsData != null) {
                updateLyricsProgress(livePos)
            }
            mainHandler.postDelayed(this, 16) // ~60fps
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupFloatingWindow()
        mainHandler.post(frameRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverIp = intent?.getStringExtra("SERVER_IP") ?: "192.168.1.100:8999"
        connectWebSocket(serverIp)
        return START_STICKY
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

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            300, // 增加高度以容纳双行大字号影视字幕与阴影
            layoutParamsType,
            // 核心 Flag：完全不抢占遥控器焦点，手势/点击彻底穿透
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM // 默认吸附在屏幕底部（看视频时不遮挡人脸）
            y = 70 // 距离底部边距 (黄金视线比例)
        }

        windowManager.addView(containerLayout, params)
    }

    private fun connectWebSocket(serverIp: String) {
        webSocket?.close(1000, "重新连接")
        val wsUrl = if (serverIp.startsWith("ws://")) serverIp else "ws://$serverIp/ws"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val type = root.optString("type")
                    val data = root.optJSONObject("data") ?: return

                    when (type) {
                        "init", "track_change" -> {
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
                                    lyricsView.updateLine("", emptyList())
                                }
                            }
                        }
                        "sync" -> {
                            val state = data.optString("state", "")
                            isPlaying = (state == "playing")
                            currentPositionMs = data.optLong("position_ms", 0L)
                            lastSyncTime = System.currentTimeMillis()
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
        var activeLineObj: JSONObject? = null

        for (i in 0 until lines.length()) {
            val line = lines.getJSONObject(i)
            val start = line.optLong("start")
            val end = line.optLong("end")
            if (posMs in start until end) {
                activeLineObj = line
                break
            }
        }

        if (activeLineObj != null) {
            val text = activeLineObj.optString("text")
            val wordsArray = activeLineObj.optJSONArray("words")
            val wordList = mutableListOf<KaraokeLyricsView.WordSegment>()

            if (wordsArray != null) {
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
            lyricsView.updateLine(text, wordList)
            lyricsView.setProgress(posMs)
        }
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
