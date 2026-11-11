package com.menufogon.core.text

import kotlinx.serialization.Serializable
import java.text.Normalizer

/**
 * A piece of text with its position on a page.
 * [x] is the left edge and [y] the baseline, with y growing downwards (top of page = 0).
 * [width] and [height] are optional (0 when unknown, e.g. hand-made data).
 */
@Serializable
data class TextItem(
    val text: String,
    val x: Float,
    val y: Float,
    val page: Int = 0,
    val width: Float = 0f,
    val height: Float = 0f,
)

object TextNormalizer {
    private val marks = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9ñ]+")

    /** Lowercase, no accents, no punctuation, single spaces. "ñ" is kept. */
    fun normalize(text: String): String {
        val lower = text.lowercase().replace("ñ", "\u0001")
        val stripped = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(marks, "")
        return stripped.replace("\u0001", "ñ").replace(nonAlnum, " ").trim()
    }

    /** Cleans a name for display: collapses spaces and removes stray trailing dots and spaces. */
    fun cleanDisplay(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ', ',', ';', ':').trim()
}
