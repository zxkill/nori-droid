package org.zxkill.nori.settings

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.zxkill.nori.settings.datastore.Language
import org.zxkill.nori.settings.datastore.SpeechOutputDevice
import org.zxkill.nori.settings.datastore.Theme
import org.zxkill.nori.settings.datastore.UserSettings
import org.zxkill.nori.util.toStateFlowDistinctBlockingFirst
import javax.inject.Inject

@HiltViewModel
class MainSettingsViewModel @Inject constructor(
    application: Application,
    private val dataStore: DataStore<UserSettings>
) : AndroidViewModel(application) {
    // run blocking because the settings screen cannot start if settings have not been loaded yet
    val settingsState = dataStore.data
        .toStateFlowDistinctBlockingFirst(viewModelScope)

    private fun updateData(transform: (UserSettings.Builder) -> Unit) {
        viewModelScope.launch {
            dataStore.updateData {
                it.toBuilder()
                    .apply(transform)
                    .build()
            }
        }
    }

    fun setLanguage(value: Language) =
        updateData { it.setLanguage(value) }
    fun setTheme(value: Theme) =
        updateData { it.setTheme(value) }
    fun setSpeechOutputDevice(value: SpeechOutputDevice) =
        updateData { it.setSpeechOutputDevice(value) }
    // Устанавливаем время отображения результата скилла
    fun setSkillOutputDisplaySeconds(value: Int) =
        updateData { it.setSkillOutputDisplaySeconds(value) }
}
