package com.pwnotes.agent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pwnotes.agent.ai.SubAgentCoordinator
import com.pwnotes.agent.data.AppWhitelistManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var whitelistManager: AppWhitelistManager

    override fun onCreate() {
        super.onCreate()
        whitelistManager = AppWhitelistManager(this)
        showFloatingBubble()
    }

    private fun showFloatingBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1E293B"))
            setPadding(24, 20, 24, 20)
            elevation = 16f
        }

        val title = TextView(this).apply {
            text = "AI Notes Agent"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 0, 0, 10)
        }
        container.addView(title)

        val captureBtn = Button(this).apply {
            text = "+ Capture Page"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            textSize = 11f
            setOnClickListener {
                val currentPkg = AccessibilityMonitorService.currentPackage.value
                if (!whitelistManager.isAppAllowed(currentPkg)) {
                    Toast.makeText(
                        this@FloatingOverlayService,
                        "Capture blocked: App not in allowed whitelist!",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                val bitmap = ScreenCaptureService.instance?.captureCurrentScreen()
                if (bitmap != null) {
                    SubAgentCoordinator.addCapturedPage(bitmap)
                    Toast.makeText(
                        this@FloatingOverlayService,
                        "Page ${SubAgentCoordinator.capturedCount.value} captured!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@FloatingOverlayService, "Screen capture not active", Toast.LENGTH_SHORT).show()
                }
            }
        }
        container.addView(captureBtn)

        val generateBtn = Button(this).apply {
            text = "Generate Notes"
            setBackgroundColor(Color.parseColor("#16A34A"))
            setTextColor(Color.WHITE)
            textSize = 11f
            setOnClickListener {
                if (SubAgentCoordinator.capturedCount.value == 0) {
                    Toast.makeText(this@FloatingOverlayService, "No pages captured yet!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Toast.makeText(this@FloatingOverlayService, "Transcribing notes faithfully...", Toast.LENGTH_LONG).show()
                scope.launch {
                    SubAgentCoordinator.startTranscriptionPipeline()
                }
            }
        }
        container.addView(generateBtn)

        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(container, layoutParams)
                        return true
                    }
                }
                return false
            }
        })

        overlayView = container
        windowManager?.addView(container, layoutParams)
    }

    override fun onDestroy() {
        overlayView?.let { windowManager?.removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
