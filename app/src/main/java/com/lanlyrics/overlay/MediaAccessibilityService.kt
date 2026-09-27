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
 * 具备以下能力：
 * 1. 自动感知 Netflix / Disney+ / HBO 播放界面与剧名季集 (如 Stranger Things S4:E1)
 * 2. 看到一半(续播)自适应：通过多重时间正则与 SeekBar 属性抓取当前续播进度
 * 3. 官方台词文本锚点拦截：通过对比当前台词在剧本中的位置，实现毫秒级绝对硬对齐
 */
class MediaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MediaA11y"
        private val TARGET_PACKAGES = setOf(
            "com.netflix.ninja",                 // Netflix Android TV
            "com.netflix.mediaclient",           // Netflix 手机/平板
            "com.disney.disneyplus",             // Disney+
            "com.amazon.amazonvideo.livingroom", // Prime Video TV
            "com.amazon.avod.thirdpartyclient",   // Prime Video Mobile
            "com.wbd.stream"                     // Max (HBO)
        )

        // 季集匹配正则，如 S4:E1, S04E01, Season 4 Episode 1, 第4季 第1集
        private val SEASON_EPISODE_PATTERN = Pattern.compile(
            """(?i)(?:s(?:eason)?\s*(\d+)[\s:x_e-]+(?:ep?|episode)?\s*(\d+))|(?:第\s*(\d+)\s*季\s*第\s*(\d+)\s*集)"""
        )

        // 双时间戳匹配正则，如 03:45 / 45:10 或 1:23:45 / 2:10:00
        private val DUAL_TIME_PATTERN = Pattern.compile(
            """(\d{1,2}:\d{2}(?::\d{2})?)\s*[/／]\s*(\d{1,2}:\d{2}(?::\d{2})?)"""
        )

        // 单时间戳匹配正则 (很多续播只显示当前时间，如 23:15 或 -31:45)
        private val SINGLE_TIME_PATTERN = Pattern.compile(
            """^-?\s*(\d{1,2}:\d{2}(?::\d{2})?)$"""
        )

        // 过滤非台词的常见 UI 关键字
        private val UI_BLACKLIST_WORDS = setOf(
            "下一集", "选集", "音频与字幕", "倍速", "锁屏", "返回", "播放", "暂停", "重播", "快进", "快退",
            "next episode", "episodes", "audio & subtitles", "speed", "lock", "skip intro", "跳过片头",
            "skip recap", "跳过回顾", "10秒", "10s", "30s", "1.0x", "1.25x", "1.5x", "0.75x", "0.5x"
        )
    }

    private var lastMatchedTitle = ""
    private var lastRequestTime = 0L
    private var lastCalibrateMs = 0L
    private var lastCalibrateTime = 0L
    private var lastAnchorText = ""
    private var lastAnchorTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        } catch (e: Throwable) {
            Log.w(TAG, "微调 serviceInfo 异常，使用 XML 默认配置: ${e.message}")
        }
        Log.i(TAG, "影视无障碍感知服务已连接 (SubSync Auto Sense)！")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TARGET_PACKAGES) return

        try {
            val rootNode = rootInActiveWindow ?: return
            val textList = mutableListOf<String>()
            collectAllTextsAndNodes(rootNode, textList)

            // 1. 扫描剧集名称与季集信息
            detectTitleAndEpisode(textList, pkg)

            // 2. 扫描时间进度（支持从头播与看到一半续播）
            detectPlaybackTime(textList)

            // 3. 扫描官方字幕台词锚点进行语义对齐与实时双语补齐
            detectSubtitleTextAnchor(textList)

        } catch (e: Throwable) {
            Log.e(TAG, "扫描无障碍节点异常: ${e.message}")
        }
    }

    private fun collectAllTextsAndNodes(node: AccessibilityNodeInfo?, result: MutableList<String>) {
        if (node == null) return

        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrEmpty() && txt.length < 150) {
            result.add(txt)
        }
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc.length < 150 && desc != txt) {
            result.add(desc)
        }

        // 检查 SeekBar / 进度条的直接属性 (看到一半时直接读进度比例)
        if (node.className?.contains("SeekBar", ignoreCase = true) == true ||
            node.className?.contains("ProgressBar", ignoreCase = true) == true) {
            node.rangeInfo?.let { range ->
                if (range.max > 0 && range.current > 0) {
                    val ratio = range.current / range.max
                    // 如果拿到当前进度秒数 (有些播放器直接以秒为单位)
                    if (range.max > 180) { // 大于3分钟，很可能是秒数
                        val ms = (range.current * 1000).toLong()
                        dispatchCalibrateTime(ms)
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            collectAllTextsAndNodes(node.getChild(i), result)
        }
    }

    private fun detectTitleAndEpisode(texts: List<String>, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastRequestTime < 10000) return // 10秒防抖

        var seriesTitle = ""
        var seasonEpisodeStr = ""

        // 第一步：寻找季集字符串 (如 "第 1 季第 2 集：處理湯米敵" 或 "S1:E2 Tackle Tommy Dixon")
        for (text in texts) {
            val matcher = SEASON_EPISODE_PATTERN.matcher(text)
            if (matcher.find()) {
                seasonEpisodeStr = text.trim()
                break
            }
        }

        // 第二步：寻找主剧名（排除季集行、黑名单按钮词汇、纯数字或时间）
        for (text in texts) {
            val t = text.trim()
            if (t.length in 2..35 &&
                t != seasonEpisodeStr &&
                !t.contains(":") && !t.contains("/") &&
                !SEASON_EPISODE_PATTERN.matcher(t).find() &&
                t.lowercase() !in UI_BLACKLIST_WORDS &&
                !t.matches(Regex("^[0-9\\s/\\-]+$"))) {
                seriesTitle = t
                break
            }
        }

        // 繁简转换 (绅士追杀令等常见港台繁体转简体，提升字幕匹配率)
        seriesTitle = toSimplifiedChinese(seriesTitle)
        seasonEpisodeStr = toSimplifiedChinese(seasonEpisodeStr)

        val fullTitle = when {
            seriesTitle.isNotEmpty() && seasonEpisodeStr.isNotEmpty() -> "$seriesTitle $seasonEpisodeStr"
            seriesTitle.isNotEmpty() -> seriesTitle
            seasonEpisodeStr.isNotEmpty() -> seasonEpisodeStr
            else -> ""
        }

        if (fullTitle.isNotEmpty() && fullTitle != lastMatchedTitle) {
            lastMatchedTitle = fullTitle
            lastRequestTime = now
            Log.i(TAG, "[!] 自动捕获到完整流媒体剧集: $fullTitle ($pkg)")

            val intent = Intent(this, FloatingLyricsService::class.java).apply {
                action = "ACTION_AUTO_MATCH_SUBTITLE"
                putExtra("MEDIA_TITLE", fullTitle)
                putExtra("PACKAGE_NAME", pkg)
            }
            startService(intent)
        }
    }

    private fun toSimplifiedChinese(input: String): String {
        return input.replace("紳士追殺令", "绅士追杀令")
            .replace("處理湯米敵", "处理汤米敌")
            .replace("怪奇物語", "怪奇物语")
            .replace("三體", "三体")
            .replace("絕命毒師", "绝命毒师")
            .replace("黑鏡", "黑镜")
    }

    private fun detectPlaybackTime(texts: List<String>) {
        for (text in texts) {
            // 优先且唯一完全可信：双时间戳 23:15 / 54:00
            val dualMatcher = DUAL_TIME_PATTERN.matcher(text)
            if (dualMatcher.find()) {
                val curTimeStr = dualMatcher.group(1) ?: continue
                val curMs = parseTimeStringToMs(curTimeStr)
                if (curMs > 0) {
                    dispatchCalibrateTime(curMs)
                    return
                }
            }

            // 备选匹配明确带有负号的倒计时时间戳 (如 -31:45，绝不匹配系统时钟 01:56)
            val t = text.trim()
            if (t.startsWith("-")) {
                val singleMatcher = SINGLE_TIME_PATTERN.matcher(t)
                if (singleMatcher.find()) {
                    val curTimeStr = singleMatcher.group(1) ?: continue
                    val curMs = parseTimeStringToMs(curTimeStr)
                    if (curMs > 0) {
                        dispatchCalibrateTime(curMs)
                        return
                    }
                }
            }
        }
    }

    private fun detectSubtitleTextAnchor(texts: List<String>) {
        val now = System.currentTimeMillis()
        for (text in texts) {
            val t = text.trim()
            val lower = t.lowercase()
            if (lower in UI_BLACKLIST_WORDS) continue
            // 排除剧名、分集名、时间戳
            if (t == lastMatchedTitle || SEASON_EPISODE_PATTERN.matcher(t).find()) continue
            if (t.matches(Regex("^[0-9\\s/\\-:]+$"))) continue

            // 台词判断：长度在 4 到 120 之间，不含 UI 常用功能词
            if (t.length in 4..120) {
                if (t != lastAnchorText || now - lastAnchorTime > 2500) {
                    lastAnchorText = t
                    lastAnchorTime = now
                    Log.d(TAG, "[*] 捕获到流媒体官方字幕台词: $t")
                    val intent = Intent(this, FloatingLyricsService::class.java).apply {
                        action = "ACTION_ANCHOR_SUBTITLE_TEXT"
                        putExtra("ANCHOR_TEXT", t)
                    }
                    startService(intent)
                    break
                }
            }
        }
    }

    private fun dispatchCalibrateTime(targetMs: Long) {
        val now = System.currentTimeMillis()
        // 1秒内不要重复矫正相同的时间
        if (Math.abs(targetMs - lastCalibrateMs) < 1000 && now - lastCalibrateTime < 1500) {
            return
        }
        lastCalibrateMs = targetMs
        lastCalibrateTime = now

        Log.i(TAG, "[*] 正在校准播放进度: ${targetMs / 1000} 秒")
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = "ACTION_CALIBRATE_TIME"
            putExtra("CALIBRATE_POSITION_MS", targetMs)
        }
        startService(intent)
    }

    private fun parseTimeStringToMs(timeStr: String): Long {
        val parts = timeStr.replace("-", "").trim().split(":")
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
