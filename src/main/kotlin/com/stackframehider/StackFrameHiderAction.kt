package com.stackframehider

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.openapi.util.IconLoader

class StackFrameHiderAction : ToggleAction("Hide Library Stack Frames", "Toggle library stack frame visibility",
    IconLoader.getIcon("/icons/ideIcon@20x20.svg", StackFrameHiderAction::class.java)) {

    private val logger = Logger.getInstance(StackFrameHiderAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false

        val settings = project.getService(StackFrameHiderSettings::class.java)
        return settings.isHideLibraryFrames
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        val settings = project.getService(StackFrameHiderSettings::class.java)

        val wasHiding = settings.isHideLibraryFrames
        settings.isHideLibraryFrames = state

        logger.warn("Stack frame hiding toggled: $wasHiding -> $state")

        // Force refresh current debug session if active
        val debuggerManager = XDebuggerManager.getInstance(project)
        val currentSession = debuggerManager.currentSession

        if (currentSession != null && currentSession.isPaused) {
            logger.warn("Current session is paused, forcing refresh")

            ApplicationManager.getApplication().invokeLater {
                try {
                    val listener = project.getService(DebugSessionListener::class.java)

                    listener.applyFilteringToUIWrapper()
                    if (!settings.isHideLibraryFrames){
                        currentSession.rebuildViews()
//                        listener.framesComponent = null
                    }
                    logger.warn("Forced debug session refresh completed")
                } catch (e: Exception) {
                    logger.error("Failed to refresh debug session", e)
                }
            }
        } else {
            logger.warn("No active paused debug session found")
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        e.presentation.isEnabled = project != null

        if (project == null) {
            return
        }

        val debuggerManager = XDebuggerManager.getInstance(project)
        val currentSession = debuggerManager.currentSession

        if (currentSession != null && currentSession.isPaused) {
            e.presentation.description = "Toggle library frame visibility (active debug session)"
        } else {
            e.presentation.description = "Toggle library frame visibility for future debug sessions"
        }
    }
}