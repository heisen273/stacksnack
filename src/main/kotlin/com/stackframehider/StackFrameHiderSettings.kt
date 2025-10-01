package com.stackframehider

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service(Service.Level.PROJECT)
class StackFrameHiderSettings {

    // Simple in-memory state, defaults to false (don't hide frames by default)
    var isHideLibraryFrames: Boolean = false
}