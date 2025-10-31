package com.stackframehider

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.frame.*
import com.intellij.openapi.diagnostic.Logger
import com.stackframehider.settings.StackFrameHiderSettings
import java.io.File

class StackFrameFilter {
    private val logger = Logger.getInstance(StackFrameFilter::class.java)

    fun isProjectFrame(frame: XStackFrame?, project: Project): Boolean {
        if (frame == null) return false

        val settings = StackFrameHiderSettings.getInstance()
        val sourcePosition = frame.sourcePosition ?: return false
        val filePath = sourcePosition.file.path

        // Check if it's a library file based on user patterns
        if (isLibraryFile(filePath, settings.libraryPatterns)) {
            return false
        }

        // If "hide only non-project frames" is enabled, check project directory
        if (settings.isInProjectEnabled) {
            val projectBasePath = project.basePath ?: return true
            val projectPath = File(projectBasePath).canonicalPath
            val isInProject = filePath.startsWith(projectPath)
            return isInProject
        }

        // If checkbox is disabled, consider all non-library frames as "project" frames
        return true
    }

    private fun isLibraryFile(filePath: String, patterns: List<String>): Boolean =
        patterns.any { pattern ->

            // Normalize dot notation to path
            val normalized = if ('.' in pattern) pattern.replace('.', '/') else pattern

            filePath.contains(normalized, ignoreCase = true)
        }

    fun extractLibraryDirName(filePath: String): String? {

        // Generic attempt: extract directory name
        val file = File(filePath)
        val parentDir = file.parentFile?.name
        return parentDir
    }
}