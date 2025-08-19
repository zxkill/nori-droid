package org.zxkill.nori.ui.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import org.zxkill.nori.ui.eyes.EyeExpression
import org.zxkill.nori.ui.eyes.EyesState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlin.math.min

/** Размер вектора признаков лица, возвращаемого моделью MobileFaceNet. */
const val FACE_DESCRIPTOR_SIZE = 192


/**
 * Порог совпадения для L2-нормализованных векторов MobileFaceNet.
 * Если расстояние меньше, лица считаются одинаковыми.
 */
private const val MATCH_THRESHOLD = 0.9f

/**
 * Насколько лучше должно быть совпадение относительно второго кандидата,
 * чтобы считать распознавание достоверным.
 */
private const val MATCH_MARGIN = 0.2f

/**
 * Представляет лицо, обнаруженное в текущем кадре.
 *
 * `id` — идентификатор, присваиваемый ML Kit. Он остаётся стабильным
 * между кадрами, пока детектор видит одно и то же лицо, что позволяет
 * запоминать знакомых людей.
 * `box` — прямоугольник в координатах исходного изображения.
 */
data class TrackedFace(val id: Int?, val box: Rect, val descriptor: FloatArray?)

/**
 * Описание известного лица с именем и приоритетом.
 *
 * `priority` используется при выборе цели для слежения, если в кадре
 * несколько знакомых людей. Чем больше значение, тем важнее лицо.
 */
data class KnownFace(
    val name: String,
    val priority: Int,
    // Один человек может быть сохранён с нескольких ракурсов,
    // поэтому храним набор векторов признаков.
    val descriptors: List<List<Float>>,
)

/**
 * Состояние и вспомогательные классы для трекинга лица.
 * Камера работает постоянно, а вычисленные смещения передаются
 * в [EyesState], чтобы глаза "смотрели" на пользователя.
 */
@Stable
class FaceTrackerState internal constructor(
    /** Видоискатель для отображения превью камеры в режиме отладки */
    val previewView: PreviewView,
    /** Список всех найденных лиц в координатах исходного изображения */
    val faces: MutableState<List<TrackedFace>>,
    /** Размер текущего кадра камеры */
    val imageSize: MutableState<Pair<Int, Int>?>,
    /** Смещения по осям yaw/pitch в градусах (для вывода в отладке) */
    val offsets: MutableState<Pair<Float, Float>?>,
    /** Библиотека известных лиц из настроек пользователя */
    val library: MutableState<Map<Int, KnownFace>>,
    /**
     * Распознанные лица в кадре. Информация сохраняется между кадрами,
     * пока детектор выдаёт одинаковый `trackingId` для одного человека.
     */
    val known: MutableState<Map<Int, KnownFace>>,
    /**
     * Идентификатор лица, за которым сейчас идёт слежение.
     * В отладочном интерфейсе подсветка и подпись привязаны именно к нему.
     */
    val activeId: MutableState<Int?>,
) {
    /** Добавить лицо в библиотеку известных по его [id]. */
    fun addKnownFace(id: Int, name: String, priority: Int = 0, descriptor: List<Float>) {
        val existing = library.value[id]
        library.value = if (existing != null) {
            library.value + (id to existing.copy(
                priority = priority,
                descriptors = existing.descriptors + listOf(descriptor)
            ))
        } else {
            library.value + (id to KnownFace(name, priority, listOf(descriptor)))
        }
    }

    /** Удалить лицо из библиотеки известных. */
    fun removeKnownFace(id: Int) {
        library.value = library.value - id
    }
}

/**
 * Создаёт и запускает трекер лица.
 *
 * Трекер настроен на максимальную эффективность:
 *  - детектор лица работает в точном режиме без лишних опций;
 *  - кадры анализируются в разрешении 640×480;
 *  - частота работы камеры ограничена 5 кадрами в секунду (можно отключить);
 *  - в ручном режиме анализируется каждый кадр для быстрого отклика;
 *  - если предыдущий кадр ещё в работе, следующий сразу закрывается;
 *  - в авто-режиме анализ кадров полностью пропускается для экономии энергии.
 * Даже при отключённом [debug] камера и анализ продолжают работать,
 * просто превью не выводится на экран.
 *
 * @param debug     показывать ли отладочный вид с превью камеры
 * @param eyesState состояние глаз, куда передаются рассчитанные смещения
 * @param limitFps  ограничивать ли частоту кадров камеры до 5 fps
 */
@Composable
fun rememberFaceTracker(
    debug: Boolean,
    eyesState: EyesState,
    limitFps: Boolean = true,
): FaceTrackerState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val embedder = remember { FaceEmbedder(context) }

    // Координаты точки, куда должны смотреть глаза. Обновляется анализатором кадров.
    val target = remember { AtomicReference(0f to 0f) }

    val state = remember {
        FaceTrackerState(
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER },
            mutableStateOf(emptyList()),
            mutableStateOf<Pair<Int, Int>?>(null),
            mutableStateOf<Pair<Float, Float>?>(null),
            mutableStateOf(emptyMap()),
            mutableStateOf(emptyMap()),
            mutableStateOf(null),
        )
    }

    LaunchedEffect(Unit) {
        // Плавно интерполируем направление взгляда, чтобы глаза не дёргались.
        var smoothX = 0f
        var smoothY = 0f
        while (true) {
            if (!eyesState.autoMode) {
                val (tx, ty) = target.get()
                // Экспоненциальное сглаживание координат
                smoothX += (tx - smoothX) * SMOOTHING
                smoothY += (ty - smoothY) * SMOOTHING
                // Переводим нормализованные координаты в градусы обзора
                state.offsets.value = Pair(smoothX * FOV_DEG_X / 2f, smoothY * FOV_DEG_Y / 2f)
                eyesState.lookAt(smoothX, smoothY)
            } else {
                // В авто-режиме глаза двигаются сами, смещения не показываем
                state.offsets.value = null
            }
            // Частота обновления ~60 Гц
            delay(16L)
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Настраиваем камеру и анализатор кадров. Перезапускается при смене режима отладки.
    DisposableEffect(debug) {
        val executor = Executors.newSingleThreadExecutor()
        // Детектор лиц ML Kit. Включаем трекинг, чтобы получать стабильные `trackingId`.
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // Точный режим лучше распознаёт лицо под углом, пусть и чуть медленнее
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .enableTracking()
                .build()
        )
        // Дополнительный детектор поз — позволяет грубо оценить положение головы,
        // когда обычный детектор лиц временно теряет человека.
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
        // Сглаженные значения направления взгляда, вычисляются без привязки к основной корутине
        var smoothX = 0f
        var smoothY = 0f
        // Строим use case анализа с возможным ограничением частоты кадров камеры
        val analysisBuilder = ImageAnalysis.Builder()
        if (limitFps) {
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(5, 5), // снижает нагрев модуля камеры
                )
        }
        val analysis = analysisBuilder
            // Анализируем кадры в повышенном разрешении для более устойчивого детектора
            .setTargetResolution(android.util.Size(640, 480))
            // Берём только последний кадр, чтобы не накапливать очередь
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { imageProxy ->
            // Если предыдущий кадр ещё обрабатывается или это не тот кадр,
            // который нужно анализировать, сразу его закрываем.
            // В авто-режиме проверяем лишь каждый десятый кадр, чтобы
            // снизить нагрузку, но в ручном режиме анализируем каждый кадр.
            val step = if (eyesState.autoMode) 10 else 1
            if (isProcessing || frameCounter++ % step != 0) {
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
                    state.faces.value = emptyList()
                    state.imageSize.value = null
                    state.offsets.value = null
                    state.activeId.value = null
                    eyesState.setExpression(EyeExpression.NORMAL)
                }
                // Сначала пытаемся обнаружить лицо
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        if (faces.isNotEmpty()) {
                            lastSeen = System.currentTimeMillis()
                            eyesState.setAutoMode(false)
                            state.imageSize.value = Pair(image.width, image.height)

                            val frameBitmap = imageProxy.toBitmap()
                            val tracked = faces.map { face ->
                                val box = face.boundingBox
                                val mirrored = Rect(
                                    image.width - box.right,
                                    box.top,
                                    image.width - box.left,
                                    box.bottom
                                )
                                val desc = extractDescriptor(frameBitmap, face, embedder)
                                TrackedFace(face.trackingId, mirrored, desc)
                            }
                            // Сохраняем все лица для дальнейшего вывода в отладочном окне
                            state.faces.value = tracked

                            // Сопоставляем найденные лица с библиотекой известных
                            // и запоминаем тех, кого уже узнавали ранее. Это позволяет
                            // сохранять подпись даже если в текущем кадре дескриптор
                            // лица не удалось построить (человек отвернулся).
                            val previous = state.known.value
                            val recognized = mutableMapOf<Int, KnownFace>()
                            val library = state.library.value
                            tracked.forEach { t ->
                                val id = t.id
                                val desc = t.descriptor
                                if (id != null) {
                                    val already = previous[id]
                                    if (already != null) {
                                        // Лицо уже известно с прошлых кадров —
                                        // сохраняем подпись независимо от текущего дескриптора
                                        recognized[id] = already
                                    } else if (desc != null) {
                                        var best: KnownFace? = null
                                        var bestDist = Float.MAX_VALUE
                                        var secondDist = Float.MAX_VALUE
                                        // Сравниваем найденное лицо со всеми сохранёнными выборками
                                        library.values.forEach { face ->
                                            val dist = face.descriptors
                                                .minOfOrNull { d -> distance(desc, d) } ?: return@forEach
                                            if (dist < bestDist) {
                                                secondDist = bestDist
                                                bestDist = dist
                                                best = face
                                            } else if (dist < secondDist) {
                                                secondDist = dist
                                            }
                                        }
                                        // Считаем лицо знакомым, только если оно заметно ближе,
                                        // чем остальные, и расстояние ниже порога.
                                        if (best != null &&
                                            bestDist < MATCH_THRESHOLD &&
                                            secondDist - bestDist > MATCH_MARGIN) {
                                            recognized[id] = best!!
                                        }
                                    }
                                }
                            }
                            state.known.value = recognized

                            val knownMap = recognized
                            // Отбираем лица, которые уже сохранены пользователем как "знакомые"
                            val knownFaces = faces.filter { knownMap.containsKey(it.trackingId) }
                            // Приоритет: если есть знакомые лица — выбираем самое важное,
                            // иначе ориентируемся на самое крупное лицо в кадре
                            val targetFace = if (knownFaces.isNotEmpty()) {
                                knownFaces.maxByOrNull { knownMap[it.trackingId]?.priority ?: 0 }
                            } else {
                                faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                            }

                            if (targetFace != null) {
                                val box = targetFace.boundingBox
                                val mirrored = Rect(
                                    image.width - box.right,
                                    box.top,
                                    image.width - box.left,
                                    box.bottom
                                )
                                // Запоминаем активное лицо — за ним будут следить глаза
                                state.activeId.value = targetFace.trackingId
                                // Если лицо знакомо, показываем радостное выражение
                                if (knownMap.containsKey(targetFace.trackingId)) {
                                    eyesState.setExpression(EyeExpression.HAPPY)
                                } else {
                                    eyesState.setExpression(EyeExpression.NORMAL)
                                }
                                // Центральная точка рамки
                                val cx = mirrored.exactCenterX()
                                val cy = mirrored.exactCenterY()
                                val px = image.width.toFloat()
                                val py = image.height.toFloat()
                                // Нормализуем координаты в диапазон [-1; 1]
                                val normX = (cx - px / 2f) / (px / 2f)
                                val normY = (cy - py / 2f) / (py / 2f)
                                // Переводим координаты в целевое смещение глаз и зеркалим X
                                val targetX = (-normX * 1.5f).coerceIn(-1f, 1f)
                                val targetY = (normY * 1.5f).coerceIn(-1f, 1f)
                                target.set(targetX to targetY)
                            }
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
                                        state.faces.value = listOf(TrackedFace(null, mirrored, null))
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
            // При выводе превью также можно ограничить частоту кадров камеры
            val previewBuilder = Preview.Builder()
            if (limitFps) {
                Camera2Interop.Extender(previewBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(5, 5),
                    )
            }
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

// Извлекает эмбеддинг лица при помощи нейросетевой модели.
// На вход подаётся обрезанный фрагмент кадра, соответствующий лицу.
// Результат — нормализованный 192‑мерный вектор признаков.


private fun extractDescriptor(frame: Bitmap, face: com.google.mlkit.vision.face.Face, embedder: FaceEmbedder): FloatArray? {
    val box = face.boundingBox
    val x = box.left.coerceAtLeast(0)
    val y = box.top.coerceAtLeast(0)
    val w = (box.right.coerceAtMost(frame.width) - x)
    val h = (box.bottom.coerceAtMost(frame.height) - y)
    if (w <= 0 || h <= 0) return null
    val cropped = Bitmap.createBitmap(frame, x, y, w, h)
    return embedder.embed(cropped)
}

/**
 * Евклидово расстояние между двумя векторами признаков.
 * Несовместимые по длине векторы считаются бесконечно удалёнными.
 */
private fun distance(a: FloatArray, b: List<Float>): Float {
    if (a.size != b.size) return Float.POSITIVE_INFINITY
    var sum = 0f
    for (i in a.indices) {
        val diff = a[i] - b[i]
        sum += diff * diff
    }
    return kotlin.math.sqrt(sum)
}

/**
 * Отладочный элемент: показывает превью камеры с рамками найденных лиц,
 * подписями известных людей и рассчитанными углами поворота.
 * Используется только по желанию пользователя, чтобы убедиться,
 * что трекинг работает корректно.
 */
@Composable
fun FaceDebugView(state: FaceTrackerState, modifier: Modifier = Modifier) {
    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.GREEN
            textSize = 32f
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView({ state.previewView }, modifier = Modifier.fillMaxSize())
        Canvas(modifier = Modifier.fillMaxSize()) {
            val img = state.imageSize.value
            val faces = state.faces.value
            if (img != null) {
                val (iw, ih) = img
                val scale = min(size.width / iw, size.height / ih)
                val offsetX = (size.width - iw * scale) / 2f
                val offsetY = (size.height - ih * scale) / 2f
                // Рисуем рамку вокруг каждого найденного лица
                faces.forEach { face ->
                    val box = face.box
                    val left = offsetX + box.left * scale
                    val top = offsetY + box.top * scale
                    val width = box.width() * scale
                    val height = box.height() * scale
                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 5f)
                    )
                    // Если лицо нам известно, подписываем его имя над рамкой
                    val name = state.known.value[face.id]?.name
                    if (name != null) {
                        drawContext.canvas.nativeCanvas.drawText(
                            name,
                            left,
                            top - 4f,
                            textPaint
                        )
                    }
                }
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
// Увеличенное значение обеспечивает более быстрое следование за движением лица
private const val SMOOTHING = 1.0f


private fun ImageProxy.toBitmap(): Bitmap {
    val nv21 = yuv420ToNv21(this)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()
    var bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    val matrix = Matrix()
    matrix.postRotate(imageInfo.rotationDegrees.toFloat())
    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    return bmp
}

private fun yuv420ToNv21(image: ImageProxy): ByteArray {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    return nv21
}
