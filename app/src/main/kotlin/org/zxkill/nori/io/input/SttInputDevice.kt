package org.zxkill.nori.io.input

import kotlinx.coroutines.flow.StateFlow

interface SttInputDevice {
    val uiState: StateFlow<SttState>

    fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean

    fun stopListening()

    fun onClick(eventListener: (InputEvent) -> Unit)

    suspend fun destroy()
}
