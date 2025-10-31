package com.stackframehider.settings

import com.intellij.openapi.options.Configurable
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.CellEditorListener
import javax.swing.event.ChangeEvent
import javax.swing.table.DefaultTableModel
import javax.swing.undo.*
import java.awt.event.KeyEvent
import kotlin.math.min
import java.util.EventObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.Alarm
import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XDebuggerManager
import com.stackframehider.DebugSessionListener


class StackFrameHiderConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private var patternsTable: JTable? = null
    private var tableModel: DefaultTableModel? = null
    private val undoManager = CompoundUndoManager()
    private var originalCellValue = ""

    private var isInProjectCheckBox: JCheckBox? = null

    override fun getDisplayName(): String = "StackSnack - Library Frame Hider"

    override fun createComponent(): JComponent {
        mainPanel = JPanel(BorderLayout()).apply {
            border = EmptyBorder(20, 20, 20, 20)
        }
        val currentSettings = StackFrameHiderSettings.getInstance()

        val contentPanel = JPanel(BorderLayout(0, 20))

        // Top section - Settings
        val settingsPanel = createSettingsPanel(currentSettings)
        contentPanel.add(settingsPanel, BorderLayout.NORTH)

        // Middle section - Patterns table
        val patternsPanel = createPatternsPanel(currentSettings)
        contentPanel.add(patternsPanel, BorderLayout.CENTER)

        mainPanel!!.add(contentPanel, BorderLayout.CENTER)
        return mainPanel!!
    }

    private fun createSettingsPanel(currentSettings: StackFrameHiderSettings): JPanel {
        val settingsPanel = JPanel()
        settingsPanel.layout = BoxLayout(settingsPanel, BoxLayout.Y_AXIS)
        settingsPanel.border = EmptyBorder(0, 0, 10, 0)

        // Option: Hide only non-project frames checkbox
        isInProjectCheckBox = JCheckBox("Treat frames outside this project as library frames").apply {
            toolTipText = """
                <html>
                When enabled, stack frames outside your project directory<br>
                are treated as library frames and hidden.
                </html>
            """.trimIndent()
            isSelected = currentSettings.isInProjectEnabled
            alignmentX = Component.LEFT_ALIGNMENT
            border = EmptyBorder(1, 0, 0, 0)
        }

        settingsPanel.add(isInProjectCheckBox!!)

        return settingsPanel
    }

    private fun createPatternsPanel(currentSettings: StackFrameHiderSettings): JPanel {
        val patternsPanel = JPanel(BorderLayout(0, 0))
        patternsPanel.border = BorderFactory.createTitledBorder("Library Patterns to Hide")

        // Instructions
        val instructionsPanel = JPanel()
        instructionsPanel.layout = BoxLayout(instructionsPanel, BoxLayout.Y_AXIS)
        instructionsPanel.border = EmptyBorder(5, 10, 5, 10)

        val mainInstructions = JLabel(
            "<html>Add patterns to match library paths. Frames containing these patterns will be hidden.</html>"
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.PLAIN, 12f)
        }
        instructionsPanel.add(mainInstructions)

        val tableInstructions = JLabel(
            "<html><i>Double-click/start typing to edit, press Enter to save, or Backspace to remove rows</i></html>"
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.ITALIC, 11f)
            foreground = Color.GRAY
            border = EmptyBorder(2, 0, 0, 0)
        }
        instructionsPanel.add(tableInstructions)

        patternsPanel.add(instructionsPanel, BorderLayout.NORTH)

        // Table setup
        tableModel = object : DefaultTableModel(arrayOf("Pattern"), 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true
        }
        currentSettings.libraryPatterns.distinct().forEach { pattern ->
            tableModel!!.addRow(arrayOf(pattern))
        }

        patternsTable = object : JTable(tableModel) {
            override fun processKeyBinding(ks: KeyStroke, e: KeyEvent, condition: Int, pressed: Boolean): Boolean {
                if (!pressed) return super.processKeyBinding(ks, e, condition, pressed)

                if (e.isMetaDown && e.keyCode == KeyEvent.VK_Z) {
                    if (e.isShiftDown && undoManager.canRedo()) undoManager.redo()
                    else if (undoManager.canUndo()) undoManager.undo()
                    repaint()
                    return true
                }

                if (e.isShiftDown && (e.keyCode == KeyEvent.VK_UP || e.keyCode == KeyEvent.VK_DOWN)) {
                    return super.processKeyBinding(ks, e, condition, pressed)
                }

                if ((e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE) && !isEditing) {
                    deleteSelectedRows()
                    return true
                }

                return super.processKeyBinding(ks, e, condition, pressed)
            }

            override fun editCellAt(row: Int, column: Int, e: EventObject?): Boolean {
                val result = super.editCellAt(row, column, e)
                if (result) {
                    SwingUtilities.invokeLater {
                        val editorComponent = getEditorComponent()
                        editorComponent?.requestFocusInWindow()
                    }
                }
                return result
            }
        }.apply {
            tableHeader.reorderingAllowed = false
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            rowHeight = 25
        }

        val textField = JTextField()
        val editor = DefaultCellEditor(textField)
        patternsTable!!.setDefaultEditor(String::class.java, editor)

        patternsTable!!.addPropertyChangeListener { evt ->
            if (evt.propertyName == "tableCellEditor" && evt.newValue != null) {
                val row = patternsTable!!.editingRow
                val col = patternsTable!!.editingColumn
                if (row >= 0 && col >= 0) {
                    originalCellValue = patternsTable!!.getValueAt(row, col).toString()
                }
            }
        }

        editor.addCellEditorListener(object : CellEditorListener {
            override fun editingStopped(e: ChangeEvent) {
                val row = patternsTable!!.editingRow
                val col = patternsTable!!.editingColumn
                if (row >= 0 && col >= 0) {
                    val newValue = patternsTable!!.getValueAt(row, col).toString().trim()
                    if (newValue.isNotEmpty() && tableModel!!.dataVector.any {
                            it[0].toString() == newValue && it != tableModel!!.dataVector[row]
                        }) {
                        JOptionPane.showMessageDialog(
                            mainPanel, "Pattern '$newValue' already exists.", "Duplicate Pattern", JOptionPane.WARNING_MESSAGE
                        )
                        tableModel!!.setValueAt(originalCellValue, row, col)
                        return
                    }
                    if (newValue.isEmpty()) deleteRow(row)
                    else if (newValue != originalCellValue) {
                        undoManager.addEdit(CellEditUndoableEdit(tableModel!!, row, col, originalCellValue, newValue))
                    }
                }
            }

            override fun editingCanceled(e: ChangeEvent) {
                if (patternsTable!!.editingRow >= 0 && patternsTable!!.editingColumn >= 0) {
                    tableModel!!.setValueAt(originalCellValue, patternsTable!!.editingRow, patternsTable!!.editingColumn)
                }
            }
        })

        val scrollPane = JScrollPane(patternsTable).apply {
            preferredSize = Dimension(0, 300)
        }
        patternsPanel.add(scrollPane, BorderLayout.CENTER)

        // Buttons panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 5))
        buttonPanel.add(JButton("Add Pattern").apply {
            addActionListener { addRow("") }
        })
        buttonPanel.add(JButton("Remove Selected").apply {
            addActionListener { deleteSelectedRows() }
        })

        patternsPanel.add(buttonPanel, BorderLayout.SOUTH)
        return patternsPanel
    }

    private fun addRow(value: String) {
        val rowIndex = tableModel!!.rowCount
        tableModel!!.addRow(arrayOf(value))
        undoManager.addEdit(AddRowUndoableEdit(tableModel!!, rowIndex, value))
        if (value.isEmpty()) {
            patternsTable!!.editCellAt(rowIndex, 0)
            patternsTable!!.editorComponent?.requestFocus()
        }
    }

    private fun deleteRow(row: Int) {
        if (row < 0 || row >= tableModel!!.rowCount) return
        val value = tableModel!!.getValueAt(row, 0).toString()
        tableModel!!.removeRow(row)
        undoManager.addEdit(DeleteRowUndoableEdit(tableModel!!, row, value))
    }

    private fun deleteSelectedRows() {
        val selectedRows = patternsTable!!.selectedRows.sortedDescending()
        if (selectedRows.isNotEmpty()) {
            undoManager.startCompoundEdit()
            selectedRows.forEach { if (it < tableModel!!.rowCount) deleteRow(it) }
            undoManager.endCompoundEdit()
            updateTableSelection(if (selectedRows.last() > 0) selectedRows.last() - 1 else 0)
        }
    }

    private fun updateTableSelection(row: Int) {
        patternsTable!!.clearSelection()
        if (tableModel!!.rowCount > 0 && row >= 0) {
            val newIndex = min(row, tableModel!!.rowCount - 1)
            patternsTable!!.setRowSelectionInterval(newIndex, newIndex)
        }
        patternsTable!!.requestFocus()
        patternsTable!!.revalidate()
        patternsTable!!.repaint()
    }

    override fun isModified(): Boolean {
        val settings = StackFrameHiderSettings.getInstance()

        val currentPatterns = (0 until tableModel!!.rowCount)
            .map { tableModel!!.getValueAt(it, 0).toString().trim() }
            .filter { it.isNotEmpty() }

        val hideNonProjectChanged = isInProjectCheckBox?.isSelected != settings.isInProjectEnabled

        return hideNonProjectChanged || settings.libraryPatterns != currentPatterns
    }

    override fun apply() {
        val settings = StackFrameHiderSettings.getInstance()

        settings.libraryPatterns.clear()
        (0 until tableModel!!.rowCount)
            .map { tableModel!!.getValueAt(it, 0).toString().trim() }
            .filter { it.isNotEmpty() }
            .forEach { settings.libraryPatterns.add(it) }

        isInProjectCheckBox?.let {
            settings.isInProjectEnabled = it.isSelected
        }

        // Refresh all active debug sessions across all open projects
        refreshAllDebugSessions()
    }

    private fun refreshAllDebugSessions() {
        ApplicationManager.getApplication().invokeLater {
            val projectManager = ProjectManager.getInstance()

            for (project in projectManager.openProjects) {
                if (project.isDisposed) continue

                val debuggerManager = XDebuggerManager.getInstance(project)
                val currentSession = debuggerManager.currentSession

                // Only refresh if there's an active paused session
                if (currentSession != null && currentSession.isPaused) {
                    val projectSettings = project.getService(StackFrameHiderSettings::class.java)

                    try {
                        // First, always rebuild views to get the original unfiltered frame list
                        currentSession.rebuildViews()

                        // Then, if hiding is enabled, apply the new filtering after a short delay
                        // to ensure rebuildViews has completed
                        if (projectSettings.isHideLibraryFrames) {
                            Alarm(Alarm.ThreadToUse.SWING_THREAD).addRequest({
                                try {
                                    val listener = project.getService(DebugSessionListener::class.java)
                                    listener.applyFilteringToUIWrapper()

                                    Logger.getInstance(
                                        StackFrameHiderConfigurable::class.java
                                    ).warn("Refreshed debug session for project: ${project.name}")
                                } catch (e: Exception) {
                                    Logger.getInstance(
                                        StackFrameHiderConfigurable::class.java
                                    ).error("Failed to apply filtering for project: ${project.name}", e)
                                }
                            }, 100) // 100ms delay to let rebuildViews complete
                        }
                    } catch (e: Exception) {
                        Logger.getInstance(
                            StackFrameHiderConfigurable::class.java
                        ).error("Failed to refresh debug session for project: ${project.name}", e)
                    }
                }
            }
        }
    }
    override fun reset() {
        val settings = StackFrameHiderSettings.getInstance()
        tableModel!!.rowCount = 0
        settings.libraryPatterns.distinct().forEach { tableModel!!.addRow(arrayOf(it)) }
        isInProjectCheckBox?.isSelected = settings.isInProjectEnabled
    }
}

// Undo/Redo support classes
class CompoundUndoManager {
    private val undoManager = UndoManager()
    private var compoundEdit: CompoundEdit? = null

    fun undo() { if (undoManager.canUndo()) undoManager.undo() }
    fun redo() { if (undoManager.canRedo()) undoManager.redo() }
    fun addEdit(edit: UndoableEdit) { compoundEdit?.addEdit(edit) ?: undoManager.addEdit(edit) }
    fun startCompoundEdit() { compoundEdit = CompoundEdit() }
    fun endCompoundEdit() { compoundEdit?.end(); compoundEdit?.let { undoManager.addEdit(it) }; compoundEdit = null }
    fun canUndo(): Boolean = undoManager.canUndo()
    fun canRedo(): Boolean = undoManager.canRedo()
}

class AddRowUndoableEdit(private val model: DefaultTableModel, private val row: Int, private val value: String) : AbstractUndoableEdit() {
    override fun undo() { super.undo(); model.removeRow(row) }
    override fun redo() { super.redo(); model.insertRow(row, arrayOf(value)) }
}

class DeleteRowUndoableEdit(private val model: DefaultTableModel, private val row: Int, private val value: String) : AbstractUndoableEdit() {
    override fun undo() { super.undo(); model.insertRow(row, arrayOf(value)) }
    override fun redo() { super.redo(); model.removeRow(row) }
}

class CellEditUndoableEdit(
    private val model: DefaultTableModel,
    private val row: Int,
    private val column: Int,
    private val oldValue: String,
    private val newValue: String
) : AbstractUndoableEdit() {
    override fun undo() { super.undo(); model.setValueAt(oldValue, row, column) }
    override fun redo() { super.redo(); model.setValueAt(newValue, row, column) }
}