package com.menufogon.core.text

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/** One character as reported by a PDF library, in reading order within its line. */
data class Glyph(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val fontSize: Float,
    val page: Int,
)

/**
 * Turns runs of glyphs into word-level [TextItem]s. Both the desktop tool and the
 * Android app use this, so they produce the same items from the same PDF.
 */
object WordBuilder {

    /** [run] is a sequence of glyphs that the PDF library reported as one piece of a line. */
    fun words(run: List<Glyph>): List<TextItem> {
        val out = mutableListOf<TextItem>()
        val current = mutableListOf<Glyph>()

        fun flush() {
            if (current.isEmpty()) return
            val first = current.first()
            val last = current.last()
            out += TextItem(
                text = current.joinToString("") { it.text },
                x = round2(first.x),
                y = round2(first.y),
                page = first.page,
                width = round2(last.x + last.width - first.x),
                height = round2(current.maxOf { it.fontSize }),
            )
            current.clear()
        }

        for (glyph in run) {
            if (glyph.text.isBlank()) {
                flush()
                continue
            }
            val previous = current.lastOrNull()
            if (previous != null) {
                val gap = glyph.x - (previous.x + previous.width)
                val size = max(glyph.fontSize, previous.fontSize)
                val sameLine = abs(glyph.y - previous.y) <= size * 0.3f
                // A wide gap or a jump backwards means another cell of the grid.
                if (!sameLine || gap > size * 0.25f || gap < -size) flush()
            }
            current += glyph
        }
        flush()
        return out
    }

    private fun round2(value: Float): Float = round(value * 100f) / 100f
}
