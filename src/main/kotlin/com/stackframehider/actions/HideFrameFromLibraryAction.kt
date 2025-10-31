// Description: Right-click action to hide stack frames from a specific library in the debugger.
package com.stackframehider.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.openapi.ui.Messages
import com.stackframehider.DebugSessionListener
import com.stackframehider.HiddenStackFrame
import com.stackframehider.StackFrameFilter
import com.stackframehider.settings.StackFrameHiderSettings

class HideFrameFromLibraryAction : AnAction("Hide Frames from This Library") {
    private val logger = Logger.getInstance(HideFrameFromLibraryAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val debuggerManager = XDebuggerManager.getInstance(project)
        val currentSession = debuggerManager.currentSession ?: return
        val currentFrame = currentSession.currentStackFrame ?: return

        if (currentFrame is HiddenStackFrame) {
            Messages.showInfoMessage(
                project,
                if (currentFrame.myCount == 1)
                    "This frame is already hidden"
                else
                    "These frames are already hidden",
                "StackSnack"
            )
            return
        }

        val sourcePosition = currentFrame.sourcePosition ?: run {
            Messages.showWarningDialog(
                project,
                "Cannot extract library pattern from this frame",
                "StackSnack"
            )
            return
        }

        val filePath = sourcePosition.file.path
        val filter = StackFrameFilter()
        val pattern = filter.extractLibraryDirName(filePath)

        if (pattern.isNullOrBlank()) {
            Messages.showWarningDialog(
                project,
                "Could not determine library pattern from: $filePath",
                "StackSnack"
            )
            return
        }

        val settings = StackFrameHiderSettings.getInstance()

        // Check if pattern already exists
        if (settings.libraryPatterns.any { it.contains(pattern, ignoreCase = true) }) {
            Messages.showInfoMessage(
                project,
                "Pattern '$pattern' is already in the hide list",
                "StackSnack"
            )
            return
        }

        // Add pattern
        settings.libraryPatterns.add(pattern)
        logger.warn("Added library pattern: $pattern")

        // Refresh if hiding is enabled
        val projectSettings = project.getService(StackFrameHiderSettings::class.java)
        if (projectSettings.isHideLibraryFrames && currentSession.isPaused) {
            val listener = project.getService(DebugSessionListener::class.java)
            listener.applyFilteringToUIWrapper()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val debuggerManager = XDebuggerManager.getInstance(project)
        val currentSession = debuggerManager.currentSession
        val currentFrame = currentSession?.currentStackFrame

        e.presentation.isEnabledAndVisible = currentFrame != null && currentSession.isPaused
    }
}