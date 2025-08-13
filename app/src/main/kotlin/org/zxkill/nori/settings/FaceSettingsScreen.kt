package org.zxkill.nori.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import org.zxkill.nori.R
import org.zxkill.nori.ui.eyes.rememberEyesState
import org.zxkill.nori.ui.face.FaceDebugView
import org.zxkill.nori.ui.face.KnownFace
import org.zxkill.nori.ui.face.rememberFaceTracker

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

    // При изменении настроек обновляем карту известных лиц в трекере
    LaunchedEffect(known) {
        tracker.known.value = known.mapValues { KnownFace(it.value.name, it.value.priority) }
    }

    var name by remember { mutableStateOf("") }
    var priorityText by remember { mutableStateOf("0") }
    val activeId = tracker.activeId.value

    Column(modifier.width(320.dp)) {
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        OutlinedTextField(
            value = priorityText,
            onValueChange = { priorityText = it.filter { ch -> ch.isDigit() } },
            label = { Text(stringResource(R.string.known_face_priority_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        Button(
            onClick = {
                val id = activeId ?: return@Button
                val priority = priorityText.toIntOrNull() ?: 0
                viewModel.addKnownFace(id, name, priority)
                tracker.addKnownFace(id, name, priority)
                name = ""
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
            known.forEach { (id, face) ->
                Text(
                    text = "${face.name} (id=$id, p=${face.priority})",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }
    }
}
