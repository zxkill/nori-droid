package org.zxkill.nori.screenshot

import org.zxkill.nori.di.WakeDeviceWrapper
import org.zxkill.nori.io.wake.WakeState

class FakeWakeDeviceWrapper : WakeDeviceWrapper {
    override val state = kotlinx.coroutines.flow.MutableStateFlow<WakeState?>(null)

    override fun download() {}
    override fun processFrame(audio16bitPcm: ShortArray): Boolean = false
    override fun frameSize(): Int = 1312
    override fun reinitializeToReleaseResources() {}
}
