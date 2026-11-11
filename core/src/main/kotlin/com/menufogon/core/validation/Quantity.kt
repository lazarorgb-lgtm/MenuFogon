package com.menufogon.core.validation

sealed interface QuantityResult {
    data class Valid(val value: Int) : QuantityResult
    data object NotANumber : QuantityResult
    data object OutOfRange : QuantityResult
}

object QuantityValidator {
    const val MIN = 0
    const val MAX = 9999

    fun isValid(value: Int): Boolean = value in MIN..MAX

    /** Blank text means 0. Only whole numbers from 0 to 9999 are accepted. */
    fun parse(text: String): QuantityResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return QuantityResult.Valid(0)
        if (!trimmed.all { it in '0'..'9' }) return QuantityResult.NotANumber
        val value = trimmed.toIntOrNull() ?: return QuantityResult.OutOfRange
        return if (isValid(value)) QuantityResult.Valid(value) else QuantityResult.OutOfRange
    }
}
