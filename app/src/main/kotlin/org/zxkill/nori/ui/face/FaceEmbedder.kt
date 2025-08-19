package org.zxkill.nori.ui.face

import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * Обёртка над нейросетевой моделью MobileFaceNet.
 * Модель принимает изображение лица 112x112 и возвращает
 * 192-мерный вектор признаков. Он нормализуется по L2,
 * что позволяет напрямую сравнивать лицевые описания
 * с помощью обычного евклидова расстояния.
 */
class FaceEmbedder(context: Context) {
    private val interpreter: Interpreter
    private val inputBuffer = ByteBuffer.allocateDirect(1 * 112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
    private val outputBuffer = Array(1) { FloatArray(FACE_DESCRIPTOR_SIZE) }

    init {
        // Загружаем tflite-модель из assets
        val bytes = context.assets.open("face_recognition.tflite").use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(bytes)
        modelBuffer.rewind()
        interpreter = Interpreter(modelBuffer)
    }

    /**
     * Получить вектор признаков лица из bitmap-изображения.
     * Функция потокобезопасна, поэтому может вызываться
     * из разных потоков анализатора.
     */
    @Synchronized
    fun embed(face: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(face, 112, 112, true)
        inputBuffer.rewind()
        for (y in 0 until 112) {
            for (x in 0 until 112) {
                val px = scaled.getPixel(x, y)
                // Нормализуем значения в диапазон [-1, 1]
                inputBuffer.putFloat(((px shr 16 and 0xFF) - 127.5f) / 128f)
                inputBuffer.putFloat(((px shr 8 and 0xFF) - 127.5f) / 128f)
                inputBuffer.putFloat(((px and 0xFF) - 127.5f) / 128f)
            }
        }
        interpreter.run(inputBuffer, outputBuffer)
        return l2Normalize(outputBuffer[0])
    }

    /**
     * Нормализация вектора по L2-норме.
     * Это повышает устойчивость сравнения признаков.
     */
    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0f
        for (v in vec) sum += v * v
        val norm = kotlin.math.sqrt(sum)
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }
}

