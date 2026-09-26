package com.lanlyrics.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
        }

        val titleView = TextView(this).apply {
            text = "局域网影视双语字幕 & 悬浮歌词"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
        }
        layout.addView(titleView)

        val descView = TextView(this).apply {
            text = "请输入 NAS 服务地址 (已默认填好):"
            textSize = 16f
            setPadding(0, 30, 0, 10)
            setTextColor(0xFFAAAAAA.toInt())
        }
        layout.addView(descView)

        val prefs = getSharedPreferences("lyrics_cfg", Context.MODE_PRIVATE)
        val ipEdit = EditText(this).apply {
            setText(prefs.getString("server_ip", "192.168.200.120:8990"))
            textSize = 18f
        }
        layout.addView(ipEdit)

        val startBtn = Button(this).apply {
            text = "启动悬浮歌词服务"
            textSize = 18f
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    return@setOnClickListener
                }

                val ipText = ipEdit.text.toString().trim()
                prefs.edit().putString("server_ip", ipText).apply()

                val serviceIntent = Intent(this@MainActivity, FloatingLyricsService::class.java).apply {
                    putExtra("SERVER_IP", ipText)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this@MainActivity, "悬浮歌词服务已启动！可直接退回主页", Toast.LENGTH_SHORT).show()
                finish() // 启动后直接退出界面，保持后台服务运行
            }
        }
        layout.addView(startBtn)

        val a11yBtn = Button(this).apply {
            text = "【开启】流媒体自动感知服务 (无障碍)"
            textSize = 16f
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this@MainActivity, "请在列表中找到【局域网影视双语字幕】并开启", Toast.LENGTH_LONG).show()
            }
        }
        layout.addView(a11yBtn)

        val stopBtn = Button(this).apply {
            text = "停止服务"
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingLyricsService::class.java))
                Toast.makeText(this@MainActivity, "服务已停止", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(stopBtn)

        setContentView(layout)
    }
}
