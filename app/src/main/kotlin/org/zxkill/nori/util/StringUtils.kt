package org.zxkill.nori.util

import org.nori.skill.util.nfkdNormalizeWord
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max

object StringUtils {
    private val PUNCTUATION_PATTERN = Pattern.compile("\\p{Punct}")
    private val WORD_DELIMITERS_PATTERN = Pattern.compile("[^\\p{L}\\d]")

    /**
     * Удаляет пунктуацию из строки.
     * @param string исходная строка
     * @return строка без символов пунктуации
     */
    fun removePunctuation(string: String): String {
        return RegexUtils.replaceAll(PUNCTUATION_PATTERN, string, "")
    }

    private fun cleanStringForDistance(s: String): String {
        return WORD_DELIMITERS_PATTERN.matcher(
            nfkdNormalizeWord(s.lowercase(Locale.getDefault()))
        ).replaceAll("")
    }

    /**
     * Возвращает матрицу динамического программирования для расстояния Левенштейна.
     * Решение находится в `memory[a.length()][b.length()]`.
     * Используется для вычисления пути преобразования одной строки в другую.
     * @param a первая строка (предварительно очищена [cleanStringForDistance])
     * @param b вторая строка (предварительно очищена [cleanStringForDistance])
     * @return матрица размером `(a.length()+1) x (b.length()+1)`
     */
    private fun levenshteinDistanceMemory(a: String, b: String): Array<IntArray> {
        // массивы инициализируются нулями по умолчанию
        val memory = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) {
            memory[i][0] = i
        }
        for (j in 0..b.length) {
            memory[0][j] = j
        }

        for (i in a.indices) {
            for (j in b.indices) {
                val substitutionCost = if (a[i].lowercaseChar() == b[j].lowercaseChar()) 0 else 1
                memory[i + 1][j + 1] = minOf(
                    memory[i][j + 1] + 1,
                    memory[i + 1][j] + 1,
                    memory[i][j] + substitutionCost
                )
            }
        }
        return memory
    }

    private fun pathInLevenshteinMemory(
        a: String, b: String, memory: Array<IntArray>
    ): List<LevenshteinMemoryPos> {
        // идём от правого нижнего угла к левому верхнему, восстанавливая путь
        val positions: MutableList<LevenshteinMemoryPos> = ArrayList()
        var i = a.length - 1
        var j = b.length - 1
        while (i >= 0 && j >= 0) {
            val iOld = i
            val jOld = j
            var match = false
            if (memory[i + 1][j + 1] == memory[i][j + 1] + 1) {
                --i // шаг вверх
            } else if (memory[i + 1][j + 1] == memory[i + 1][j] + 1) {
                --j // шаг влево
            } else {
                match = memory[i + 1][j + 1] == memory[i][j]
                --i
                --j
            }
            positions.add(LevenshteinMemoryPos(iOld, jOld, match))
        }
        return positions
    }

    /**
     * Вычисляет расстояние Левенштейна между двумя строками.
     * Использует только две строки памяти, что снижает потребление ресурсов.
     * @see customStringDistance
     * @param aNotCleaned первая строка
     * @param bNotCleaned вторая строка
     * @return количество правок для преобразования одной строки в другую
     */
    fun levenshteinDistance(aNotCleaned: String, bNotCleaned: String): Int {
        var a = cleanStringForDistance(aNotCleaned)
        var b = cleanStringForDistance(bNotCleaned)
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        if (a.length < b.length) {
            val tmp = a
            a = b
            b = tmp
        }

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i].lowercaseChar() == b[j].lowercaseChar()) 0 else 1
                current[j + 1] = minOf(
                    previous[j + 1] + 1,
                    current[j] + 1,
                    previous[j] + cost
                )
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[b.length]
    }

    /**
     * Считает дополнительные показатели расстояния Левенштейна.
     * Используется для построения пользовательских метрик схожести строк.
     * @param a первая строка (очищена [cleanStringForDistance])
     * @param b вторая строка (очищена [cleanStringForDistance])
     * @return структура с расстоянием, максимальной длиной совпадений и общим числом совпавших символов
     */
    private fun stringDistanceStats(a: String, b: String): StringDistanceStats {
        val memory = levenshteinDistanceMemory(a, b)
        var matchingCharCount = 0
        var subsequentChars = 0
        var maxSubsequentChars = 0
        for (pos in pathInLevenshteinMemory(a, b, memory)) {
            if (pos.match) {
                ++matchingCharCount
                ++subsequentChars
                maxSubsequentChars = max(maxSubsequentChars, subsequentChars)
            } else {
                subsequentChars = max(0, subsequentChars - 1)
            }
        }
        return StringDistanceStats(
            memory[a.length][b.length], maxSubsequentChars,
            matchingCharCount
        )
    }

    /**
     * Пользовательская метрика расстояния между строками.
     * Хорошо подходит для сравнения названий объектов.
     * @param aNotCleaned первая строка
     * @param bNotCleaned вторая строка
     * @return значение, где меньше — лучше, может быть отрицательным
     */
    fun customStringDistance(aNotCleaned: String, bNotCleaned: String): Int {
        val a = cleanStringForDistance(aNotCleaned)
        val b = cleanStringForDistance(bNotCleaned)
        val stats = stringDistanceStats(a, b)
        return stats.levenshteinDistance - stats.maxSubsequentChars - stats.matchingCharCount
    }

    /**
     * Специализированная метрика расстояния для имён контактов.
     * Длина строк почти не влияет на результат.
     * @param aNotCleaned первая строка
     * @param bNotCleaned вторая строка
     * @return отрицательное значение, где больше по модулю — лучшее совпадение
     */
    fun contactStringDistance(aNotCleaned: String, bNotCleaned: String): Int {
        val a = cleanStringForDistance(aNotCleaned)
        val b = cleanStringForDistance(bNotCleaned)
        val stats = stringDistanceStats(a, b)
        return -stats.maxSubsequentChars - stats.matchingCharCount
    }

    private class LevenshteinMemoryPos(
        val i: Int,
        val j: Int,
        val match: Boolean
    )

    private class StringDistanceStats(
        val levenshteinDistance: Int,
        val maxSubsequentChars: Int,
        val matchingCharCount: Int
    )
}

/**
 * Преобразует строку в нижний регистр, делая заглавной только первую букву.
 * @param locale текущая локаль пользователя
 */
fun String.lowercaseCapitalized(locale: Locale): String {
    return lowercase(locale).replaceFirstChar { it.titlecase(locale) }
}
