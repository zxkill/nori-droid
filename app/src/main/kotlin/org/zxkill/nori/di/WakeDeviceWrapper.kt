package org.zxkill.nori.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.zxkill.nori.io.wake.WakeDevice
import org.zxkill.nori.io.wake.WakeState
import org.zxkill.nori.io.wake.oww.OpenWakeWordDevice
import javax.inject.Singleton

interface WakeDeviceWrapper {
    val state: StateFlow<WakeState?>
    fun download()
    fun processFrame(audio16bitPcm: ShortArray): Boolean
    fun frameSize(): Int
    fun reinitializeToReleaseResources()
}

class WakeDeviceWrapperImpl(
    @ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
) : WakeDeviceWrapper {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var currentDevice: WakeDevice = OpenWakeWordDevice(appContext, okHttpClient)
    private val _state: MutableStateFlow<WakeState?> = MutableStateFlow(null)
    override val state: StateFlow<WakeState?> = _state

    init {
        scope.launch {
            currentDevice.state.collectLatest { _state.emit(it) }
        }
    }

    override fun download() {
        currentDevice.download()
    }

    override fun processFrame(audio16bitPcm: ShortArray): Boolean {
        return currentDevice.processFrame(audio16bitPcm)
    }

    override fun frameSize(): Int = currentDevice.frameSize()

    override fun reinitializeToReleaseResources() {
        currentDevice.destroy()
        currentDevice = OpenWakeWordDevice(appContext, okHttpClient)
        scope.launch {
            currentDevice.state.collectLatest { _state.emit(it) }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
class WakeDeviceWrapperModule {
    @Provides
    @Singleton
    fun provideWakeDeviceWrapper(
        @ApplicationContext appContext: Context,
        okHttpClient: OkHttpClient,
    ): WakeDeviceWrapper {
        return WakeDeviceWrapperImpl(appContext, okHttpClient)
    }
}
