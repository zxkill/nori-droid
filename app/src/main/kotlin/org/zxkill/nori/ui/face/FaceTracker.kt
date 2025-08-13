package org.zxkill.nori.ui.face

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

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
 * Создаёт и запускает трекер лица.
 *
 * Трекер настроен на максимальную эффективность:
 *  - детектор лица работает в быстром режиме без лишних опций;
 *  - кадры анализируются в разрешении 640×480;
 *  - частота работы камеры ограничена 15 кадрами в секунду;
 *  - обрабатывается лишь каждый второй кадр, остальные игнорируются;
 *  - если предыдущий кадр ещё в работе, следующий сразу закрывается.
 * Даже при отключённом [debug] камера и анализ продолжают работать,
 * просто превью не выводится на экран.
 *
 * @param debug     показывать ли отладочный вид с превью камеры
 * @param eyesState состояние глаз, куда передаются рассчитанные смещения
 */
@Composable
fun rememberFaceTracker(debug: Boolean, eyesState: EyesState): FaceTrackerState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val target = remember { AtomicReference(0f to 0f) }

    val state = remember {
        FaceTrackerState(
            PreviewView(context),
            mutableStateOf<Rect?>(null),
            mutableStateOf<Pair<Int, Int>?>(null),
            mutableStateOf<Pair<Float, Float>?>(null),
        )
    }

    LaunchedEffect(Unit) {
        var smoothX = 0f
        var smoothY = 0f
        while (true) {
            if (!eyesState.autoMode) {
                val (tx, ty) = target.get()
                smoothX += (tx - smoothX) * SMOOTHING
                smoothY += (ty - smoothY) * SMOOTHING
                state.offsets.value = Pair(smoothX * FOV_DEG_X / 2f, smoothY * FOV_DEG_Y / 2f)
                eyesState.lookAt(smoothX, smoothY)
            } else {
                state.offsets.value = null
            }
            delay(16L)
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    DisposableEffect(debug) {
        val executor = Executors.newSingleThreadExecutor()
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // Быстрый режим и отключение лишних фич экономят батарею
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .build()
        )
        val poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                // Потоковый режим — для быстрого отклика при непрерывном анализе
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
        // Время последнего обнаружения лица (для авто-режима)
        var lastSeen = System.currentTimeMillis()
        // Флаг показывает, что текущий кадр ещё анализируется
        var isProcessing = false
        // Счётчик кадров, чтобы обрабатывать лишь каждый второй
        var frameCounter = 0
        // Сглаженные значения направления взгляда
        var smoothX = 0f
        var smoothY = 0f
        // Строим use case анализа с ограничением частоты кадров камеры
        val analysisBuilder = ImageAnalysis.Builder()
        Camera2Interop.Extender(analysisBuilder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(10, 10), // 10 fps — меньше нагрев от модуля камеры
            )
        val analysis = analysisBuilder
            // Устанавливаем невысокое разрешение кадра для снижения нагрузки
            .setTargetResolution(android.util.Size(320, 240))
            // Берём только последний кадр, чтобы не накапливать очередь
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { imageProxy ->
            // Если предыдущий кадр ещё обрабатывается или это нечётный кадр,
            // сразу его закрываем, тем самым сокращая частоту анализа
            if (isProcessing || frameCounter++ % 2 != 0) {
                imageProxy.close()
                return@setAnalyzer
            }
            isProcessing = true
            // Анализ каждого кадра выполняется в отдельном потоке
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                fun handleNoFace() {
                    val now = System.currentTimeMillis()
                    // Если лицо не появляется дольше секунды, включаем авто-режим
                    if (now - lastSeen > 1_000L) {
                        eyesState.setAutoMode(true)
                    }
                    // Сбрасываем отладочные данные
                    state.faceBox.value = null
                    state.imageSize.value = null
                    state.offsets.value = null
                }
                // Сначала пытаемся обнаружить лицо
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        // Выбираем самое крупное лицо в кадре
                        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        if (face != null) {
                            lastSeen = System.currentTimeMillis()
                            eyesState.setAutoMode(false)
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
                            // По вертикали оставляем исходный знак: при движении головы вверх лицо
                            // смещается вверх в кадре, поэтому дополнительная инверсия не нужна
                            val targetY = (normY * 1.5f).coerceIn(-1f, 1f)
                            target.set(targetX to targetY)
                            isProcessing = false
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
                                        lastSeen = System.currentTimeMillis()
                                        eyesState.setAutoMode(false)
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
                                        // Ось Y направлена вниз, но при подъёме головы лицо смещается вверх,
                                        // поэтому используем нормализованное значение без инверсии знака
                                        val targetY = (normY * 1.5f).coerceIn(-1f, 1f)
                                        target.set(targetX to targetY)
                                    } else {
                                        // Голова не найдена — сбрасываем состояние
                                        handleNoFace()
                                    }
                                }
                                .addOnFailureListener { handleNoFace() }
                                .addOnCompleteListener {
                                    isProcessing = false
                                    imageProxy.close()
                                }
                        }
                    }
                    .addOnFailureListener {
                        handleNoFace()
                        isProcessing = false
                        imageProxy.close()
                    }
            } else {
                isProcessing = false
                imageProxy.close()
            }
        }

        val cameraProvider = cameraProviderFuture.get()
        cameraProvider.unbindAll()
        val useCases = mutableListOf<UseCase>(analysis)
        if (debug) {
            // При выводе превью также ограничиваем частоту кадров камеры
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(10, 10),
                )
            val preview = previewBuilder.build().also {
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
private const val SMOOTHING = 0.15f

