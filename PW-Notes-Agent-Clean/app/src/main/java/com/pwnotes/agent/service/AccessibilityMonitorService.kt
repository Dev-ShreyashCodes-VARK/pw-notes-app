package com.pwnotes.agent.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.pwnotes.agent.data.AppWhitelistManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AccessibilityMonitorService : AccessibilityService() {

    companion object {
        private val _currentPackage = MutableStateFlow<String?>("")
        val currentPackage: StateFlow<String?> = _currentPackage

        private val _isCurrentAppAllowed = MutableStateFlow(false)
        val isCurrentAppAllowed: StateFlow<Boolean> = _isCurrentAppAllowed
    }

    private lateinit var whitelistManager: AppWhitelistManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        whitelistManager = AppWhitelistManager(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                _currentPackage.value = pkg
                val allowed = whitelistManager.isAppAllowed(pkg)
                _isCurrentAppAllowed.value = allowed
            }
        }
    }

    override fun onInterrupt() {
        _isCurrentAppAllowed.value = false
    }
}
