package com.stackframehider

import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import com.intellij.xdebugger.*
import com.intellij.openapi.diagnostic.Logger
import java.awt.Color
import com.intellij.openapi.components.Service
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.components.JBList
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import java.lang.ref.WeakReference
import java.awt.Component
import com.intellij.util.Alarm
import com.intellij.openapi.Disposable
import com.intellij.openapi.wm.ToolWindow
import javax.swing.JList
import javax.swing.ListCellRenderer
import com.intellij.ui.SimpleColoredComponent
import javax.swing.UIManager


@Service(Service.Level.PROJECT)
class DebugSessionListener(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(DebugSessionListener::class.java)
    private var messageBusConnection: MessageBusConnection? = null
    private val settings = project.getService(StackFrameHiderSettings::class.java)

    // Use WeakReference so we don't prevent garbage collection
    private var framesComponentRef: WeakReference<JBList<*>>? = null
    private var currentSession: XDebugSession? = null
    private var originalRenderer: ListCellRenderer<Any?>? = null
    private var referenceLibraryFrame: XStackFrame? = null

    // Prevent concurrent updates
    @Volatile
    private var isUpdating = false
    @Volatile
    private var retryCount = 0
    private val maxRetries = 10
    // Use Alarm for proper EDT scheduling instead of sleep
    private val updateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val updateDelayMs = 10

    private var currentFramePosition = 0

    private var contentManagerListener: ContentManagerListener? = null

    init {
        logger.warn("Initializing Stack Frame Filter Service")
        setupDebuggerListener()
        setupToolWindowListener()
    }

    private fun setupToolWindowListener() {
        messageBusConnection?.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == ToolWindowId.DEBUG && settings.isHideLibraryFrames) {
                        // Try to apply filtering when debug window becomes visible
                        scheduleUpdate(updateDelayMs)
                    }
                }
            }
        )
    }

    private fun setupDebuggerListener() {
        val debuggerManager = XDebuggerManager.getInstance(project)

        messageBusConnection = project.messageBus.connect(this)
        messageBusConnection?.subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) {
                    logger.warn("Debug process started")
                    setupDebugSession(debugProcess.session)
                }
            }
        )

        debuggerManager.currentSession?.let { session ->
            logger.warn("Found existing debug session")
            setupDebugSession(session)
        }
    }

    private fun setupDebugSession(session: XDebugSession) {
        logger.warn("Setting up debug session")
        currentSession = session
        setupContentManagerListener()

        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                logger.warn("Session paused")
                currentSession = session
                // Clear cache on pause - frames list is rebuilt
                framesComponentRef = null
                originalRenderer = null
                retryCount = 0
                // Schedule update with delay to let UI render
                scheduleUpdate(updateDelayMs)
            }

            override fun stackFrameChanged() {
                // Only update if not our hidden frame
                if (session.currentStackFrame !is HiddenStackFrame) {
                    logger.warn("Stack frame changed")
                    scheduleUpdate(updateDelayMs)
                }
            }

            override fun settingsChanged() {
                logger.warn("Settings changed")
                framesComponentRef = null
                originalRenderer = null
                retryCount = 0
                scheduleUpdate(updateDelayMs)
            }

            override fun sessionResumed() {
                logger.warn("Session resumed")
                framesComponentRef = null
                originalRenderer = null
                retryCount = 0
            }

            override fun sessionStopped() {
                logger.warn("Session stopped")
                framesComponentRef = null
                originalRenderer = null
                currentSession = null
                retryCount = 0
            }
        })
    }

    private fun setupContentManagerListener() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.DEBUG) ?: return

        contentManagerListener?.let {
            toolWindow.contentManager.removeContentManagerListener(it)
        }

        contentManagerListener = object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                logger.warn("Debug tab changed")
                // Clear cache when switching tabs - component changes
                framesComponentRef = null
                originalRenderer = null
                retryCount = 0
                scheduleUpdate(updateDelayMs)
            }
        }

        toolWindow.contentManager.addContentManagerListener(contentManagerListener!!)
    }

    /**
     * Schedule an update using Alarm instead of Thread.sleep
     * This doesn't block the EDT
     */
    private fun scheduleUpdate(delayMs: Int) {
        if (currentSession == null) {
            logger.warn("No active paused session, skipping update")
            return
        }

        if (isUpdating) {
            logger.warn("Update already in progress, will retry")
            // Schedule another attempt after current one completes
            updateAlarm.addRequest({ scheduleUpdate(updateDelayMs) }, 50)
            return
        }

        updateAlarm.cancelAllRequests()
        updateAlarm.addRequest({
            applyFilteringToUI()
        }, delayMs)
    }

    /**
     * Public method called from action
     * Already on EDT when called from action
     */
    fun applyFilteringToUIWrapper() {
        // Clear cache to force fresh lookup
        framesComponentRef = null
        originalRenderer = null
        retryCount = 0
        scheduleUpdate(0)
    }

    private fun getToolWindow(): ToolWindow? {
        val servicesToolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.SERVICES)
        val debugToolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.DEBUG)

        val toolWindow = when {
            servicesToolWindow?.isVisible == true -> servicesToolWindow
            debugToolWindow?.isVisible == true -> debugToolWindow
            else -> {
                logger.warn("Neither Debug nor Services tool window is available")
                return null
            }
        }
        return toolWindow
    }

    /**
     * Main update logic - runs on EDT via Alarm
     */
    private fun applyFilteringToUI() {
        if (isUpdating) {
            logger.warn("Concurrent update attempt blocked")
            return
        }

        if (project.isDisposed) {
            logger.warn("Project is disposed")
            return
        }

        try {
            isUpdating = true

            val activeSession = XDebuggerManager.getInstance(project).currentSession
            if (activeSession == null) {
                logger.warn("No active debug session")
                retryCount = 0  // Reset on no session
                return
            }
            currentSession = activeSession

            if (!settings.isHideLibraryFrames) {
                logger.warn("Hiding disabled")
                return
            }
            // Try DEBUG tool window first, then SERVICES tool window
            val toolWindow: ToolWindow = getToolWindow() ?: return

            val content = toolWindow.contentManager.selectedContent
            if (content == null) {
                logger.warn("No selected content")
                return
            }

            // Try cached component first
            val cachedComponent = framesComponentRef?.get()
            if (cachedComponent != null && cachedComponent.isShowing) {
                logger.warn("Attempting to use cached component")
                if (updateFramesComponent(cachedComponent)) {
                    logger.warn("Successfully updated cached component")
                    retryCount = 0  // Reset on success
                    return
                } else {
                    logger.warn("Cached component update failed, clearing cache")
                    framesComponentRef = null
                    originalRenderer = null
                }
            }

            // Find and update component
            if (!findAndUpdateFramesComponent(content.component)) {
                if (retryCount >= maxRetries) {
                    logger.warn("Max retries ($maxRetries) reached, giving up")
                    retryCount = 0
                    return
                }

                retryCount++
                logger.warn("Could not find frames component, will retry")
                // Schedule retry if component not ready yet
                scheduleUpdate(updateDelayMs)
            }

        } catch (e: Exception) {
            logger.error("Failed to update debugger UI", e)
        } finally {
            isUpdating = false
        }
    }

    /**
     * Install custom renderer to prevent flashing of hidden frames
     */
    private fun installCustomRenderer(component: JBList<*>) {
        if (originalRenderer == null) {
            originalRenderer = component.cellRenderer as? ListCellRenderer<Any?>
        }
        val origRenderer = originalRenderer ?: return

        component.cellRenderer = object : ListCellRenderer<Any?> {
            override fun getListCellRendererComponent(
                list: JList<out Any?>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {

                // this doesn't work as expected - index won't match after filtering.
                // Need to restore selection differently
//                if (isSelected){
//                    currentFramePosition = index
//                }

                // For regular frames, use original renderer
                if (value !is HiddenStackFrame) {
                    return origRenderer.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus
                    )
                }

                // For hidden frames, render with consistent styling

                val comp = SimpleColoredComponent()

                // Use slightly lighter gray for text when selected for contrast
                val textColor = if (isSelected) Color(140, 140, 140) else Color(120, 120, 120)
                comp.append(
                    value.getText(),
                    com.intellij.ui.SimpleTextAttributes(
                        com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN,
                        textColor
                    )
                )
                // Set colors
                comp.background = if (isSelected) {
                    UIManager.getColor("List.selectionBackground") ?: list.selectionBackground
                } else {
                    UIManager.getColor("FileColor.Yellow")
                }
                comp.foreground = UIManager.getColor("List.selectionForeground") ?: list.selectionForeground

                comp.isOpaque = true

                // Slightly smaller font
                comp.font = list.font?.deriveFont(list.font.size * 0.98f)

                return comp
            }
        }
    }

    /**
     * Update the frames component with filtered list
     * Returns true if successful
     */
    private fun updateFramesComponent(component: JBList<*>): Boolean {
        try {
            val modelSize = component.model.size
            if (modelSize == 0) {
                logger.warn("Model is empty")
                return false
            }

            val projectFrames = mutableListOf<XStackFrame>()
            var nonProjectFrameCount = 0
            val filter = StackFrameFilter()

            // Build filtered list
            for (i in 0 until modelSize) {
                val item = component.model.getElementAt(i) as? XStackFrame ?: continue

                if (item is HiddenStackFrame) {
                    nonProjectFrameCount += item.myCount
                    continue
                }

                if (!filter.isProjectFrame(item, project)) {
                    referenceLibraryFrame = item
                    nonProjectFrameCount++
                    continue
                }

                // Add hidden frame placeholder if we have accumulated non-project frames
                if (nonProjectFrameCount > 0) {
                    projectFrames.add(HiddenStackFrame(nonProjectFrameCount))
                    nonProjectFrameCount = 0
                }
                projectFrames.add(item)
            }

            // Add remaining hidden frames at end
            if (nonProjectFrameCount > 0) {
                // Merge with last hidden frame if it exists
                if (projectFrames.isNotEmpty() && projectFrames.last() is HiddenStackFrame) {
                    val lastHidden = projectFrames.last() as HiddenStackFrame
                    nonProjectFrameCount += lastHidden.myCount
                    projectFrames.removeAt(projectFrames.size - 1)
                }
                projectFrames.add(HiddenStackFrame(nonProjectFrameCount))
            }

            // Install custom renderer to prevent flashing
            installCustomRenderer(component)

            // Update model using reflection
            val replaceAllMethod = component.model.javaClass.getMethod("replaceAll", List::class.java)
            replaceAllMethod.invoke(component.model, projectFrames)

            logger.warn("Updated component with ${projectFrames.size} frames")

            // Restore selection to current frame
            restoreSelection(component, projectFrames)

            return true

        } catch (e: Exception) {
            logger.warn("Failed to update component: ${e.message}")
            return false
        }
    }

    private fun restoreSelection(component: JBList<*>, projectFrames: List<XStackFrame>) {
        val currentFrame = currentSession?.currentStackFrame ?: return
        val index = projectFrames.indexOfFirst { it == currentFrame }

        if (index >= 0) {
//            currentFramePosition = index
            component.selectedIndex = index
            component.ensureIndexIsVisible(index)
        }
    }

    /**
     * Recursively find the frames component in UI hierarchy
     */
    private fun findAndUpdateFramesComponent(component: Component): Boolean {
        if (!component.isShowing) {
            return false
        }

        if (component is JBList<*>) {
            // Validate this is the frames list
            if (!isFramesList(component)) {
                return false
            }

            // Cache the component
            framesComponentRef = WeakReference(component)
            logger.warn("Found and cached frames component")

            return updateFramesComponent(component)
        }

        // Recursively search children
        if (component is java.awt.Container) {
            for (child in component.components) {
                if (findAndUpdateFramesComponent(child)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Validate that a JBList is actually the frames list
     */
    private fun isFramesList(component: JBList<*>): Boolean {
        val modelSize = component.model.size

        // Empty or invalid list
        if (modelSize == 0) {
            return false
        }

        // Single null element means wrong list
        if (modelSize == 1 && component.model.getElementAt(0) == null) {
            return false
        }

        // Check if first element is a stack frame
        val firstElement = component.model.getElementAt(0)
        return firstElement is HiddenStackFrame || firstElement is XStackFrame
    }

    override fun dispose() {
        updateAlarm.cancelAllRequests()

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.DEBUG)
        contentManagerListener?.let {
            toolWindow?.contentManager?.removeContentManagerListener(it)
        }

        messageBusConnection?.disconnect()

        framesComponentRef = null
        originalRenderer = null
        currentSession = null
        contentManagerListener = null

        logger.warn("Stack Frame Filter Service disposed")
    }
}

private class HiddenStackFrame(private val count: Int) : XStackFrame() {
    var myCount = count

    override fun getEvaluator() = null
    override fun getSourcePosition() = null
    override fun getEqualityObject() = null

    override fun customizePresentation(component: ColoredTextContainer) {
        val fadedGrey = com.intellij.ui.SimpleTextAttributes(
            com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN,
            Color(120, 120, 120)
        )
        component.append(getText(), fadedGrey)
    }

    fun getText(): String = "     $count hidden frame" + if (count > 1) "s" else ""
}