package com.menufogon.core.parser

import com.menufogon.core.text.TextItem
import com.menufogon.core.text.TextNormalizer
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max

data class ParsedCourse(
    val name: String,
    val ingredients: String = "",
    val allergens: String = "",
    /** True when the text looks wrong and should be reviewed by the user. */
    val doubtful: Boolean = false,
)

enum class DayIssue {
    MISSING_FIRST,
    MISSING_SECOND,
    DOUBTFUL_TEXT,
    ORPHAN_ALLERGENS,
    WEEKDAY_MISMATCH,
}

data class ParsedDay(
    val date: LocalDate,
    val first: ParsedCourse?,
    val second: ParsedCourse?,
    /** True when the PDF explicitly says there is no first course ("FESTIVO"). */
    val noFirst: Boolean = false,
    val issues: Set<DayIssue> = emptySet(),
)

data class ParseResult(
    val days: List<ParsedDay>,
    val warnings: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = days.isEmpty()
    fun day(dayOfMonth: Int): ParsedDay? = days.firstOrNull { it.date.dayOfMonth == dayOfMonth }
}

/**
 * Reads a monthly menu grid from positioned text.
 *
 * The text is assigned to a day by the column of the day header ("LUNES 2") and by
 * the vertical order below it, never by reading order. The menu type is NOT read
 * from the document: the caller decides it.
 */
class MenuParser {

    private data class Word(val text: String, val x: Float, val xc: Float, val y: Float, val h: Float, val page: Int)
    private data class Header(val page: Int, val day: Int, val dow: DayOfWeek, val xc: Float, val y: Float, val h: Float)
    private data class Line(val y: Float, val h: Float, val text: String)
    private class RawCourse(val text: MutableList<String> = mutableListOf(), val allergens: MutableList<String> = mutableListOf())

    fun parse(items: List<TextItem>, month: YearMonth): ParseResult {
        val words = items.flatMap(::explode)
        val (headers, body) = findHeaders(words)
        if (headers.isEmpty()) {
            return ParseResult(emptyList(), listOf("No day headers found"))
        }
        val centers = columnCenters(headers)
        fun column(xc: Float): Int = centers.indices.minByOrNull { abs(centers[it] - xc) }!!

        val warnings = mutableListOf<String>()
        val days = mutableMapOf<Int, ParsedDay>()

        val headersByColumn = headers.groupBy { it.page to column(it.xc) }
        val wordsByColumn = body.groupBy { it.page to column(it.xc) }

        for ((key, columnHeaders) in headersByColumn) {
            val sorted = columnHeaders.sortedBy { it.y }
            val columnWords = wordsByColumn[key].orEmpty()
            sorted.forEachIndexed { index, header ->
                val top = header.y + max(1f, header.h * 0.25f)
                val bottom = sorted.getOrNull(index + 1)?.y?.minus(1f) ?: Float.MAX_VALUE
                val block = columnWords.filter { it.y > top && it.y < bottom }
                val date = try {
                    month.atDay(header.day)
                } catch (e: DateTimeException) {
                    warnings += "Day ${header.day} does not exist in $month"
                    return@forEachIndexed
                }
                if (days.containsKey(header.day)) {
                    warnings += "Day ${header.day} appears more than once"
                    return@forEachIndexed
                }
                days[header.day] = buildDay(date, header, toLines(block))
            }
        }
        return ParseResult(days.values.sortedBy { it.date }, warnings)
    }

    // ---- words -------------------------------------------------------------

    /** Splits an item into words, spreading the x position proportionally when the width is known. */
    private fun explode(item: TextItem): List<Word> {
        val text = item.text
        if (text.isBlank()) return emptyList()
        val perChar = if (item.width > 0f && text.isNotEmpty()) item.width / text.length else 0f
        val out = mutableListOf<Word>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) { i++; continue }
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            val x = item.x + perChar * start
            val w = perChar * (i - start)
            out += Word(text.substring(start, i), x, x + w / 2f, item.y, item.height, item.page)
        }
        return out
    }

    private fun lineTolerance(h: Float): Float = max(2f, (if (h > 0f) h else DEFAULT_HEIGHT) * 0.3f)

    // ---- headers -----------------------------------------------------------

    private fun findHeaders(words: List<Word>): Pair<List<Header>, List<Word>> {
        val used = HashSet<Word>()
        val headers = mutableListOf<Header>()
        val byPage = words.groupBy { it.page }
        for ((_, pageWords) in byPage) {
            for (w in pageWords) {
                val dow = WEEKDAYS[TextNormalizer.normalize(w.text)] ?: continue
                val tol = lineTolerance(w.h)
                // The day number is the closest word to the right on the same line.
                val next = pageWords
                    .filter { it !== w && abs(it.y - w.y) <= tol && it.x > w.x }
                    .minByOrNull { it.x } ?: continue
                val day = next.text.trim('.', ',').toIntOrNull() ?: continue
                if (day !in 1..31) continue
                // Centre of "LUNES 2": from the left edge of the name to the right edge of the number.
                val right = next.x + 2f * (next.xc - next.x)
                val center = (w.x + right) / 2f
                headers += Header(w.page, day, dow, center, w.y, w.h)
                used += w
                used += next
            }
        }
        return headers to words.filter { it !in used }
    }

    /** Clusters header centres into grid columns shared by all pages. */
    private fun columnCenters(headers: List<Header>): List<Float> {
        val rows = headers.groupBy { it.page to Math.round(it.y / 4f) }
        var minPitch = Float.MAX_VALUE
        for ((_, row) in rows) {
            val xs = row.map { it.xc }.sorted()
            for (i in 1 until xs.size) {
                val d = xs[i] - xs[i - 1]
                if (d > 1f && d < minPitch) minPitch = d
            }
        }
        val tol = if (minPitch == Float.MAX_VALUE) 30f else minPitch * 0.4f
        val clusters = mutableListOf<MutableList<Float>>()
        for (x in headers.map { it.xc }.sorted()) {
            val last = clusters.lastOrNull()
            if (last != null && x - last.average().toFloat() <= tol) last += x else clusters += mutableListOf(x)
        }
        return clusters.map { it.average().toFloat() }
    }

    // ---- lines and courses -------------------------------------------------

    private fun toLines(block: List<Word>): List<Line> {
        val sorted = block.sortedWith(compareBy<Word> { it.y }.thenBy { it.x })
        val lines = mutableListOf<MutableList<Word>>()
        for (w in sorted) {
            val last = lines.lastOrNull()
            if (last != null && abs(last.first().y - w.y) <= lineTolerance(w.h)) last += w else lines += mutableListOf(w)
        }
        return lines.map { ws ->
            Line(ws.first().y, ws.maxOf { it.h }, ws.sortedBy { it.x }.joinToString(" ") { it.text })
        }
    }

    private fun buildDay(date: LocalDate, header: Header, lines: List<Line>): ParsedDay {
        val courses = mutableListOf<RawCourse>()
        val issues = mutableSetOf<DayIssue>()
        var noFirst = false
        var current: RawCourse? = null
        var previous: Line? = null
        var previousWasText = false

        for (line in lines) {
            val h = if (line.h > 0f) line.h else DEFAULT_HEIGHT
            val gap = previous?.let { line.y - it.y } ?: 0f
            val refHeight = max(h, previous?.h?.takeIf { it > 0f } ?: DEFAULT_HEIGHT)
            when {
                TextNormalizer.normalize(line.text) == NO_COURSE_MARK -> {
                    if (courses.isEmpty()) noFirst = true
                    current = null
                    previousWasText = false
                }
                isAllergenLine(line.text) -> {
                    val target = current
                    if (target != null && gap <= refHeight * ALLERGEN_MAX_GAP) {
                        target.allergens += line.text.trim()
                    } else if (courses.size < 2) {
                        // Far-away capital text after both courses is a page title or footer: ignore it.
                        issues += DayIssue.ORPHAN_ALLERGENS
                    }
                    previousWasText = false
                }
                else -> {
                    val startsNew = current == null || !previousWasText || gap > refHeight * PARAGRAPH_MIN_GAP
                    if (startsNew) {
                        current = RawCourse().also { courses += it }
                    }
                    current!!.text += line.text.trim()
                    previousWasText = true
                }
            }
            previous = line
        }

        val parsed = courses.map(::toCourse)
        val first = if (noFirst) null else parsed.getOrNull(0)
        val second = if (noFirst) parsed.getOrNull(0) else parsed.getOrNull(1)

        if (first == null && !noFirst) issues += DayIssue.MISSING_FIRST
        if (second == null) issues += DayIssue.MISSING_SECOND
        if (first?.doubtful == true || second?.doubtful == true) issues += DayIssue.DOUBTFUL_TEXT
        if (date.dayOfWeek != header.dow) issues += DayIssue.WEEKDAY_MISMATCH

        return ParsedDay(date, first, second, noFirst, issues)
    }

    private fun toCourse(raw: RawCourse): ParsedCourse {
        val full = raw.text.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        val (namePart, ingredients) = splitIngredients(full)
        val name = TextNormalizer.cleanDisplay(namePart)
        val allergens = raw.allergens.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        val unbalanced = full.count { it == '(' } != full.count { it == ')' }
        val doubtful = unbalanced || name.count { it.isLetter() } < 3 || name.any { it.isDigit() }
        return ParsedCourse(name, ingredients, allergens, doubtful)
    }

    /**
     * Splits "Name. (ingredient, ingredient)" into name and ingredients. The ingredients
     * are the last parenthesised group at the end of the text; a parenthesis that belongs
     * to the name, as in "Bienmesabe (cazón adobado)", is kept in the name.
     */
    private fun splitIngredients(full: String): Pair<String, String> {
        val text = full.trimEnd('.', ' ')
        if (!text.endsWith(")")) {
            // Unclosed ingredient list: still separate what follows the last "(".
            val open = full.lastIndexOf('(')
            val closed = full.lastIndexOf(')')
            if (open > closed && open > 0) {
                return full.substring(0, open) to full.substring(open + 1).trim().trimEnd('.')
            }
            return full to ""
        }
        var depth = 0
        var open = -1
        for (i in text.indices.reversed()) {
            when (text[i]) {
                ')' -> depth++
                '(' -> { depth--; if (depth == 0) { open = i; break } }
            }
        }
        if (open <= 0) return full to ""
        val inner = text.substring(open + 1, text.length - 1).trim()
        val before = text.substring(0, open).trimEnd()
        val looksLikeIngredients = before.endsWith(".") || inner.contains(',') || inner.contains(" y ")
        return if (looksLikeIngredients) before to inner.trimEnd('.') else full to ""
    }

    private fun isAllergenLine(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 3 && letters.all { it.isUpperCase() }
    }

    companion object {
        private const val DEFAULT_HEIGHT = 11f
        /** A text line further than this many line heights from the previous one starts a new dish. */
        private const val PARAGRAPH_MIN_GAP = 2.0f
        /** An allergen line further than this many line heights from its dish is not attached to it. */
        private const val ALLERGEN_MAX_GAP = 3.0f
        private const val NO_COURSE_MARK = "festivo"

        private val WEEKDAYS = mapOf(
            "lunes" to DayOfWeek.MONDAY,
            "martes" to DayOfWeek.TUESDAY,
            "miercoles" to DayOfWeek.WEDNESDAY,
            "jueves" to DayOfWeek.THURSDAY,
            "viernes" to DayOfWeek.FRIDAY,
            "sabado" to DayOfWeek.SATURDAY,
            "domingo" to DayOfWeek.SUNDAY,
        )

        /** Suggests a menu type id from a file name (NORMAL, DIB, EEI). Only a hint for the user. */
        fun suggestTypeId(fileName: String): Int? {
            val n = TextNormalizer.normalize(fileName)
            val tokens = n.split(' ', '_').flatMap { it.split(Regex("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")) }
            return when {
                tokens.any { it == "eei" } -> com.menufogon.core.model.MenuType.EEI
                tokens.any { it == "dib" || it.startsWith("diab") } -> com.menufogon.core.model.MenuType.DIABETIC
                tokens.any { it == "normal" } -> com.menufogon.core.model.MenuType.NORMAL
                else -> null
            }
        }
    }
}
