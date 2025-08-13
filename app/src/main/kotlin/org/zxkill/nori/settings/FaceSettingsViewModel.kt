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
import org.zxkill.nori.settings.datastore.FaceSample
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
     * Добавить выборку лица по [name]. Если такое имя уже есть,
     * новая выборка дописывается в существующую запись.
     */
    fun addKnownFace(name: String, priority: Int, descriptor: List<Float>) {
        viewModelScope.launch {
            dataStore.updateData { settings ->
                val builder = settings.toBuilder()
                val existing = builder.knownFacesMap.entries.find { it.value.name == name }
                if (existing != null) {
                    val id = existing.key
                    val entryBuilder = existing.value.toBuilder()
                    entryBuilder.setPriority(priority)
                        .addSamples(
                            FaceSample.newBuilder()
                                .addAllDescriptor(descriptor)
                                .build(),
                        )
                    builder.putKnownFaces(id, entryBuilder.build())
                } else {
                    val id = (System.currentTimeMillis() and 0x7fffffff).toInt()
                    builder.putKnownFaces(
                        id,
                        FaceEntry.newBuilder()
                            .setName(name)
                            .setPriority(priority)
                            .addSamples(
                                FaceSample.newBuilder()
                                    .addAllDescriptor(descriptor)
                                    .build(),
                            )
                            .build(),
                    )
                }
                builder.build()
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
