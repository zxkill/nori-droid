package org.zxkill.nori.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.zxkill.nori.settings.datastore.FaceEntry
import org.zxkill.nori.settings.datastore.UserSettings

/**
 * ViewModel для управления списком известных лиц.
 * Хранит их в DataStore и предоставляет методы для добавления.
 */
@HiltViewModel
class FaceSettingsViewModel @Inject constructor(
    application: Application,
    private val dataStore: DataStore<UserSettings>,
) : AndroidViewModel(application) {

    /** Поток известных лиц из настроек пользователя */
    val knownFaces = dataStore.data.map { it.knownFacesMap }

    /**
     * Добавить новое лицо в настройки по его [id], [name], [priority] и [descriptor].
     */
    fun addKnownFace(id: Int, name: String, priority: Int, descriptor: List<Float>) {
        viewModelScope.launch {
            dataStore.updateData { settings ->
                settings.toBuilder()
                    .putKnownFaces(
                        id,
                        FaceEntry.newBuilder()
                            .setName(name)
                            .setPriority(priority)
                            .addAllDescriptor(descriptor)
                            .build()
                    )
                    .build()
            }
        }
    }

    /** Удалить сохранённое лицо по его [id]. */
    fun removeKnownFace(id: Int) {
        viewModelScope.launch {
            dataStore.updateData { settings ->
                settings.toBuilder()
                    .removeKnownFaces(id)
                    .build()
            }
        }
    }
}
