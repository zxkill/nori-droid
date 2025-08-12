package org.zxkill.nori.di

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.zxkill.nori.io.input.InputEvent
import org.zxkill.nori.io.input.SttInputDevice
import org.zxkill.nori.io.input.SttState
import org.zxkill.nori.io.input.vosk.VoskInputDevice
import org.zxkill.nori.settings.datastore.UserSettings
import javax.inject.Singleton

interface SttInputDeviceWrapper {
    val uiState: StateFlow<SttState?>
    fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?, playSound: Boolean = true): Boolean
    fun stopListening()
    fun onClick(eventListener: (InputEvent) -> Unit)
    fun reinitializeToReleaseResources()
}

class SttInputDeviceWrapperImpl(
    @ApplicationContext private val appContext: Context,
    private val localeManager: LocaleManager,
    private val okHttpClient: OkHttpClient,
) : SttInputDeviceWrapper {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var sttInputDevice: SttInputDevice = VoskInputDevice(appContext, okHttpClient, localeManager)
    private val _uiState: MutableStateFlow<SttState?> = MutableStateFlow(null)
    override val uiState: StateFlow<SttState?> = _uiState
    private var uiStateJob: Job? = null

    init {
        scope.launch { restartUiStateJob() }
    }

    private suspend fun restartUiStateJob() {
        uiStateJob?.cancel()
        uiStateJob = scope.launch {
            sttInputDevice.uiState.collect { state -> _uiState.emit(state) }
        }
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?, playSound: Boolean): Boolean {
        return sttInputDevice.tryLoad(thenStartListeningEventListener)
    }

    override fun stopListening() {
        sttInputDevice.stopListening()
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        sttInputDevice.onClick(eventListener)
    }

    override fun reinitializeToReleaseResources() {
        scope.launch {
            sttInputDevice.destroy()
            sttInputDevice = VoskInputDevice(appContext, okHttpClient, localeManager)
            restartUiStateJob()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
class SttInputDeviceWrapperModule {
    @Provides
    @Singleton
    fun provideInputDeviceWrapper(
        @ApplicationContext appContext: Context,
        dataStore: DataStore<UserSettings>,
        localeManager: LocaleManager,
        okHttpClient: OkHttpClient,
    ): SttInputDeviceWrapper {
        // dataStore is kept for compatibility but not used
        return SttInputDeviceWrapperImpl(appContext, localeManager, okHttpClient)
    }
}
