package com.aiphoneagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ActionLog.add("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The first version intentionally only observes the active UI.
        // More advanced interaction will be added after the basic APK is verified.
    }

    override fun onInterrupt() {
        ActionLog.add("Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun findTextAndClick(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) {
                    ActionLog.add("Clicked: $text")
                    return true
                }
            }
            var parent = node.parent
            repeat(4) {
                if (parent?.isClickable == true) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (result) {
                        ActionLog.add("Clicked parent of: $text")
                        return true
                    }
                }
                parent = parent?.parent
            }
        }
        return false
    }
}
