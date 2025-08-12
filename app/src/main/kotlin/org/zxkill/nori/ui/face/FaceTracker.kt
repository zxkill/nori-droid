package org.zxkill.nori.ui.face

import android.graphics.Rect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import org.zxkill.nori.ui.eyes.EyesState
import java.util.concurrent.Executors

/**
 * Состояние и вспомогательные классы для трекинга лица.
 * Камера работает постоянно, а вычисленные смещения передаются
 * в [EyesState], чтобы глаза "смотрели" на пользователя.
 */
@Stable
class FaceTrackerState internal constructor(
    /** Видоискатель для отображения превью камеры в режиме отладки */
    val previewView: PreviewView,
    /** Рамка найденного лица в координатах исходного изображения */
    val faceBox: MutableState<Rect?>,
    /** Размер текущего кадра камеры */
    val imageSize: MutableState<Pair<Int, Int>?>,
    /** Смещения по осям yaw/pitch в градусах (для вывода в отладке) */
    val offsets: MutableState<Pair<Float, Float>?>,
)

/**
 * Создаёт и запускает трекер лица. Даже если [debug] отключён,
 * камера и анализ продолжают работать, просто превью не выводится на экран.
 *
 * @param debug     показывать ли отладочный вид с превью камеры
 * @param eyesState состояние глаз, куда передаются рассчитанные смещения
 */
@Composable
fun rememberFaceTracker(debug: Boolean, eyesState: EyesState): FaceTrackerState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val state = remember {
        FaceTrackerState(
            PreviewView(context),
            mutableStateOf<Rect?>(null),
            mutableStateOf<Pair<Int, Int>?>(null),
            mutableStateOf<Pair<Float, Float>?>(null),
        )
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(debug) {
        val executor = Executors.newSingleThreadExecutor()
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
        val poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
        val analysis = ImageAnalysis.Builder()
            // Берём только последний кадр, чтобы не накапливать очередь
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { imageProxy ->
            // Анализ каждого кадра выполняется в отдельном потоке
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                // Сначала пытаемся обнаружить лицо
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        // Выбираем самое крупное лицо в кадре
                        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        if (face != null) {
                            state.imageSize.value = Pair(image.width, image.height)
                            val box = face.boundingBox
                            // Камера фронтальная, поэтому зеркалим координаты
                            val mirrored = Rect(
                                image.width - box.right,
                                box.top,
                                image.width - box.left,
                                box.bottom
                            )
                            state.faceBox.value = mirrored
                            // Нормализуем центр рамки в диапазон [-1,1]
                            val cx = mirrored.exactCenterX()
                            val cy = mirrored.exactCenterY()
                            val px = image.width.toFloat()
                            val py = image.height.toFloat()
                            val normX = (cx - px / 2f) / (px / 2f)
                            val normY = (cy - py / 2f) / (py / 2f)
                            // Увеличиваем амплитуду и ограничиваем диапазон
                            val targetX = (-normX * 1.5f).coerceIn(-1f, 1f)
                            // По вертикали инвертируем знак: в изображении ось Y направлена вниз,
                            // поэтому для совпадения с движением головы меняем направление
                            val targetY = (-normY * 1.5f).coerceIn(-1f, 1f)
                            // Плавно приближаем текущий взгляд к целевому
                            val smoothX = eyesState.lookX + (targetX - eyesState.lookX) * SMOOTHING
                            val smoothY = eyesState.lookY + (targetY - eyesState.lookY) * SMOOTHING
                            // Сохраняем углы отклонения для отладки
                            state.offsets.value = Pair(smoothX * FOV_DEG_X / 2f, smoothY * FOV_DEG_Y / 2f)
                            eyesState.lookAt(smoothX, smoothY)
                            imageProxy.close()
                        } else {
                            // Если лиц не найдено, пробуем использовать детектор поз
                            poseDetector.process(image)
                                .addOnSuccessListener { pose ->
                                    val lEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
                                    val rEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)
                                    val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
                                    val landmarks = listOfNotNull(lEar, rEar, nose)
                                        .filter { it.inFrameLikelihood >= POSE_MIN_VIS }
                                    if (landmarks.size >= 2) {
                                        state.imageSize.value = Pair(image.width, image.height)
                                        // По видимым точкам головы формируем грубую рамку
                                        val xs = landmarks.map { it.position.x }
                                        val ys = landmarks.map { it.position.y }
                                        val xMin = xs.minOrNull()!!
                                        val xMax = xs.maxOrNull()!!
                                        val yMin = ys.minOrNull()!!
                                        val yMax = ys.maxOrNull()!!
                                        val mirrored = Rect(
                                            (image.width - xMax).toInt(),
                                            yMin.toInt(),
                                            (image.width - xMin).toInt(),
                                            yMax.toInt()
                                        )
                                        state.faceBox.value = mirrored
                                        val cx = mirrored.exactCenterX()
                                        val cy = mirrored.exactCenterY()
                                        val px = image.width.toFloat()
                                        val py = image.height.toFloat()
                                        val normX = (cx - px / 2f) / (px / 2f)
                                        val normY = (cy - py / 2f) / (py / 2f)
                                        val targetX = (-normX * 1.5f).coerceIn(-1f, 1f)
                                        // Ось Y кадра направлена вниз, поэтому инвертируем знак,
                                        // чтобы глаза следовали за реальным смещением головы
                                        val targetY = (-normY * 1.5f).coerceIn(-1f, 1f)
                                        val smoothX = eyesState.lookX + (targetX - eyesState.lookX) * SMOOTHING
                                        val smoothY = eyesState.lookY + (targetY - eyesState.lookY) * SMOOTHING
                                        state.offsets.value = Pair(smoothX * FOV_DEG_X / 2f, smoothY * FOV_DEG_Y / 2f)
                                        eyesState.lookAt(smoothX, smoothY)
                                    } else {
                                        // Голова не найдена — сбрасываем состояние
                                        state.faceBox.value = null
                                        state.imageSize.value = null
                                        state.offsets.value = null
                                    }
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        }
                    }
                    .addOnFailureListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }

        val cameraProvider = cameraProviderFuture.get()
        cameraProvider.unbindAll()
        val useCases = mutableListOf<UseCase>(analysis)
        if (debug) {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(state.previewView.surfaceProvider)
            }
            useCases.add(preview)
        }
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            *useCases.toTypedArray()
        )

        onDispose {
            cameraProvider.unbindAll()
            detector.close()
            poseDetector.close()
            executor.shutdown()
        }
    }

    return state
}

/**
 * Отладочный элемент: показывает превью камеры с рамкой найденного лица
 * и рассчитанными углами поворота. Используется только по желанию
 * пользователя, чтобы убедиться, что трекинг работает корректно.
 */
@Composable
fun FaceDebugView(state: FaceTrackerState, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView({ state.previewView }, modifier = Modifier.fillMaxSize())
        Canvas(modifier = Modifier.fillMaxSize()) {
            val box = state.faceBox.value
            val img = state.imageSize.value
            if (box != null && img != null) {
                val (iw, ih) = img
                val scaleX = size.width / iw
                val scaleY = size.height / ih
                drawRect(
                    color = Color.Green,
                    topLeft = Offset(box.left * scaleX, box.top * scaleY),
                    size = Size(box.width() * scaleX, box.height() * scaleY),
                    style = Stroke(width = 5f)
                )
            }
        }
        val off = state.offsets.value
        if (off != null) {
            Text(
                text = String.format("yaw=%+.1f pitch=%+.1f", off.first, off.second),
                color = Color.Green,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

// Приближённые углы обзора фронтальной камеры в градусах
private const val FOV_DEG_X = 62f
private const val FOV_DEG_Y = 38f
// Минимальная уверенность детектора поз для учета landmark
private const val POSE_MIN_VIS = 0.8f
// Коэффициент плавности перемещения взгляда
private const val SMOOTHING = 0.4f

