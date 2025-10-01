package com.stackframehider

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.diagnostic.Logger

class DebugSessionStartupActivity : StartupActivity {
    private val logger = Logger.getInstance(DebugSessionStartupActivity::class.java)

    override fun runActivity(project: Project) {
        logger.warn("DebugSessionStartupActivity: initializing service")
        // This will force the service to be created if it hasn't been already

        project.getService(DebugSessionListener::class.java)

        logger.warn("DebugSessionStartupActivity: service initialized")
    }
}