package com.lanlyrics.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

/**
 * 影视播放无障碍自动感知服务 (MediaAccessibilityService)
 * 职责：
 * 1. 自动感知 Netflix / Disney+ / HBO 播放界面
 * 2. 毫秒级提取正在播放的影视剧名与季集数 (例如 Stranger Things S4:E1)
 * 3. 提取视频当前播放时间戳 (如 12:34 / 50:12) 自动校准字幕时间轴
 * 4. 自动通知本地悬浮窗与 NAS 加载对应双语字幕
 */
class MediaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MediaA11y"
        private val TARGET_PACKAGES = setOf(
            "com.netflix.ninja",        // Netflix Android TV
            "com.netflix.mediaclient",  // Netflix 手机/平板
            "com.disney.disneyplus",    // Disney+
            "com.amazon.amazonvideo.livingroom", // Prime Video TV
            "com.amazon.avod.thirdpartyclient",  // Prime Video Mobile
            "com.wbd.stream"            // Max (HBO)
        )

        // 季集匹配正则，如 S4:E1, S04E01, Season 4 Episode 1, 第4季 第1集
        private val SEASON_EPISODE_PATTERN = Pattern.compile(
            """(?i)(?:s(?:eason)?\s*(\d+)[\s:x_e-]+(?:ep?|episode)?\s*(\d+))|(?:第\s*(\d+)\s*季\s*第\s*(\d+)\s*集)"""
        )

        // 播放进度时间正则，如 03:45 / 45:10 或 1:23:45
        private val TIME_PROGRESS_PATTERN = Pattern.compile(
            """(\d{1,2}:\d{2}(?::\d{2})?)\s*[/／]\s*(\d{1,2}:\d{2}(?::\d{2})?)"""
        )
    }

    private var lastMatchedTitle = ""
    private var lastRequestTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = TARGET_PACKAGES.toTypedArray()
        }
        Log.i(TAG, "影视无障碍自动感知服务已就绪！监听目标流媒体平台...")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TARGET_PACKAGES) return

        val rootNode = rootInActiveWindow ?: return
        try {
            val textList = mutableListOf<String>()
            collectAllTexts(rootNode, textList)

            // 1. 扫描剧集名称与季集信息
            detectTitleAndEpisode(textList, pkg)

            // 2. 扫描时间进度并自动校准时钟
            detectPlaybackTime(textList)

        } catch (e: Exception) {
            Log.e(TAG, "扫描无障碍节点异常: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?, result: MutableList<String>) {
        if (node == null) return
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrEmpty() && txt.length < 120) {
            result.add(txt)
        }
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc.length < 120 && desc != txt) {
            result.add(desc)
        }

        for (i in 0 until node.childCount) {
            collectAllTexts(node.getChild(i), result)
        }
    }

    private fun detectTitleAndEpisode(texts: List<String>, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastRequestTime < 15000) return // 15秒防抖

        for (text in texts) {
            val matcher = SEASON_EPISODE_PATTERN.matcher(text)
            if (matcher.find()) {
                val foundTitle = text.trim()
                if (foundTitle != lastMatchedTitle) {
                    lastMatchedTitle = foundTitle
                    lastRequestTime = now
                    Log.i(TAG, "[!] 自动捕获到正在播放的剧集: $foundTitle ($pkg)")

                    // 发送 Intent 给后台 FloatingLyricsService，拉取该剧集双语字幕
                    val intent = Intent(this, FloatingLyricsService::class.java).apply {
                        action = "ACTION_AUTO_MATCH_SUBTITLE"
                        putExtra("MEDIA_TITLE", foundTitle)
                        putExtra("PACKAGE_NAME", pkg)
                    }
                    startService(intent)
                    return
                }
            }
        }
    }

    private fun detectPlaybackTime(texts: List<String>) {
        for (text in texts) {
            val matcher = TIME_PROGRESS_PATTERN.matcher(text)
            if (matcher.find()) {
                val curTimeStr = matcher.group(1) ?: continue
                val curMs = parseTimeStringToMs(curTimeStr)
                if (curMs > 0) {
                    // 通知悬浮窗瞬时校准当前播放时间戳
                    val intent = Intent(this, FloatingLyricsService::class.java).apply {
                        action = "ACTION_CALIBRATE_TIME"
                        putExtra("CALIBRATE_POSITION_MS", curMs)
                    }
                    startService(intent)
                    return
                }
            }
        }
    }

    private fun parseTimeStringToMs(timeStr: String): Long {
        val parts = timeStr.split(":")
        return try {
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val s = parts[2].toLong()
                (h * 3600 + m * 60 + s) * 1000
            } else if (parts.size == 2) {
                val m = parts[0].toLong()
                val s = parts[1].toLong()
                (m * 60 + s) * 1000
            } else 0L
        } catch (e: Exception) {
            0L
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被系统中断")
    }
}
