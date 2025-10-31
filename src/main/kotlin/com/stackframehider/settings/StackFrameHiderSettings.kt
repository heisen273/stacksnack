package com.stackframehider.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.service

@State(
    name = "StackFrameHiderSettings",
    storages = [Storage("stackFrameHiderSettings.xml")]
)
@Service(Service.Level.APP)
class StackFrameHiderSettings : PersistentStateComponent<StackFrameHiderSettings.State> {

    data class State(
        var isHideLibraryFrames: Boolean = false,
        var isInProjectEnabled: Boolean = true,
        var libraryPatterns: MutableList<String> = mutableListOf(
            "venv/",
            "site-packages/",
            "lib/python",
            "go/pkg/mod",
            "node_modules/",
        )
    )

    private var myState = State()

    var isHideLibraryFrames: Boolean
        get() = myState.isHideLibraryFrames
        set(value) {
            myState.isHideLibraryFrames = value
        }

    var isInProjectEnabled: Boolean
        get() = myState.isInProjectEnabled
        set(value) {
            myState.isInProjectEnabled = value
        }

    var libraryPatterns: MutableList<String>
        get() = myState.libraryPatterns
        set(value) {
            myState.libraryPatterns = value
        }

    override fun getState(): State {
        return myState
    }

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): StackFrameHiderSettings {
            return service<StackFrameHiderSettings>()
        }
    }
}