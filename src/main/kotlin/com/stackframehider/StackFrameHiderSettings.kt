package com.stackframehider

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.PersistentStateComponent

@State(
    name = "StackFrameHiderSettings",
    storages = [Storage("stackFrameHider.xml")]
)
@Service(Service.Level.PROJECT)
class StackFrameHiderSettings : PersistentStateComponent<StackFrameHiderSettings.State> {

    data class State(
        var isHideLibraryFrames: Boolean = false
    )

    private var myState = State()

    var isHideLibraryFrames: Boolean
        get() = myState.isHideLibraryFrames
        set(value) {
            myState.isHideLibraryFrames = value
        }

    override fun getState(): State {
        return myState
    }

    override fun loadState(state: State) {
        myState = state
    }
}