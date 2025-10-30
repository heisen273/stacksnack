package com.stackframehider

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.*
import com.intellij.openapi.diagnostic.Logger
import java.io.File

// Try to implement XDebuggerFrameFilter if available
class StackFrameFilter {
    private val logger = Logger.getInstance(StackFrameFilter::class.java)

    fun isProjectFrame(frame: XStackFrame?, project: Project): Boolean {
        if (frame == null) return false
        val projectBasePath = project.basePath ?: return true
        val sourcePosition = frame.sourcePosition ?: return false

        val filePath = sourcePosition.file.path
        val projectPath = File(projectBasePath).canonicalPath
        val isInProject = filePath.startsWith(projectPath)
        val isLibraryFile = isLibraryFile(filePath)

        return isInProject && !isLibraryFile
    }

    private fun isLibraryFile(filePath: String): Boolean {
        val libraryPatterns = listOf(
            "/site-packages/", "/lib/python", "/Library/Frameworks/Python.framework",
            "\\site-packages\\", "\\lib\\python", "venv/lib/", "venv\\lib\\",
            ".venv/lib/", ".venv\\lib\\", "/usr/lib/python", "/usr/local/lib/python",
            "threading.py", "subprocess.py", "_bootstrap.py", "runpy.py",
            "importlib", "concurrent/futures", "asyncio/", "__pycache__",
            "/_internal/", "\\__pycache__\\", "\\_internal\\", "pkgutil.py",
            "go/pkg/mod", "go\\pkg\\mod"
        )
        return libraryPatterns.any { filePath.contains(it, ignoreCase = true) }
    }

    private fun getFrameDescription(frame: XStackFrame): String {
        val sourcePosition = frame.sourcePosition
        return if (sourcePosition != null) {
            val fileName = sourcePosition.file.name
            val lineNumber = sourcePosition.line + 1
            "$fileName:$lineNumber"
        } else {
            frame.toString()
        }
    }
}