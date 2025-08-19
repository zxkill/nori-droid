package org.zxkill.nori.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import org.zxkill.nori.R
import org.zxkill.nori.ui.eyes.rememberEyesState
import org.zxkill.nori.ui.face.FaceDebugView
import org.zxkill.nori.ui.face.KnownFace
import org.zxkill.nori.ui.face.FACE_DESCRIPTOR_SIZE
import org.zxkill.nori.ui.face.rememberFaceTracker
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.Alignment

/**
 * Экран настройки известных лиц.
 * Позволяет сфотографировать лицо и сохранить его имя и приоритет.
 */
@Composable
fun FaceSettingsScreen(
    onDismiss: () -> Unit,
    viewModel: FaceSettingsViewModel = hiltViewModel(),
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            FaceSettingsContent(viewModel = viewModel, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun FaceSettingsContent(
    viewModel: FaceSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    // Для захвата изображения нужен трекер лица и состояние глаз
    val tracker = rememberFaceTracker(debug = true, eyesState = rememberEyesState(), limitFps = false)
    val known by viewModel.knownFaces.collectAsState(initial = emptyMap())

    // При изменении настроек обновляем библиотеку известных лиц в трекере.
    // Игнорируем выборки без дескрипторов или с некорректной длиной.
    LaunchedEffect(known) {
        tracker.library.value = known.mapNotNull { (id, face) ->
            val samples = face.samplesList.mapNotNull { s ->
                val d = s.descriptorList
                if (d.size == FACE_DESCRIPTOR_SIZE) d else null
            }
            if (samples.isEmpty()) null else id to KnownFace(face.name, face.priority, samples)
        }.toMap()
    }

    var name by remember { mutableStateOf("") }
    var priorityText by remember { mutableStateOf("0") }
    val activeId = tracker.activeId.value

    // Весь контент прокручиваем, чтобы список лиц и кнопка не обрезались на низких экранах
    Column(
        modifier
            .width(320.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.pref_known_faces_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        // Превью камеры с рамками лиц
        FaceDebugView(state = tracker, modifier = Modifier
            .fillMaxWidth()
            .height(200.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.known_face_input_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        // Приоритет задаёт важность лица (0–10). Чем выше число, тем раньше оно выбирается для слежения
        OutlinedTextField(
            value = priorityText,
            onValueChange = { priorityText = it.filter { ch -> ch.isDigit() } },
            label = { Text(stringResource(R.string.known_face_priority_hint)) },
            supportingText = { Text(stringResource(R.string.known_face_priority_range)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        Button(
            onClick = {
                val priority = priorityText.toIntOrNull() ?: 0
                val desc = tracker.faces.value.firstOrNull { it.id == activeId }?.descriptor
                // Сохраняем лицо только если удалось получить вектор признаков
                if (desc != null) {
                    viewModel.addKnownFace(name, priority, desc.toList())
                    name = ""
                }
            },
            enabled = activeId != null && name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.known_face_add_button))
        }
        if (known.isEmpty()) {
            Text(
                text = stringResource(R.string.known_faces_empty),
                modifier = Modifier.padding(16.dp)
            )
        } else {
            Text(
                text = stringResource(R.string.known_faces_list_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
            )
            known.forEach { (id, face) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.known_face_item_format,
                            face.name,
                            face.priority,
                            face.samplesCount,
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        viewModel.removeKnownFace(id)
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.known_face_delete))
                    }
                }
            }
        }
    }
}
