package com.menufogon.core.prep

import com.menufogon.core.model.DailyEntry
import com.menufogon.core.text.TextNormalizer
import java.time.LocalDate

data class DishSource(val typeId: Int, val typeName: String, val count: Int)

data class DishPrep(
    val name: String,
    val allergens: String,
    val total: Int,
    val sources: List<DishSource>,
) {
    /** E.g. "Pollo a la jardinera: 15 (Normal 8, EEI 7)". */
    fun describe(): String = "$name: $total (" + sources.joinToString(", ") { "${it.typeName} ${it.count}" } + ")"
}

sealed interface PreparationResult {
    /** There is no saved entry for the date: nothing must be shown as zero. */
    data class NoEntry(val date: LocalDate) : PreparationResult

    data class Ready(
        val date: LocalDate,
        val totalDiners: Int,
        val totalFirsts: Int,
        val totalSeconds: Int,
        val firsts: List<DishPrep>,
        val seconds: List<DishPrep>,
    ) : PreparationResult
}

object PreparationCalculator {

    fun calculate(date: LocalDate, entry: DailyEntry?): PreparationResult {
        if (entry == null) return PreparationResult.NoEntry(date)
        val firsts = group(entry) { it.firstName to it.firstAllergens }
        val seconds = group(entry) { it.secondName to it.secondAllergens }
        return PreparationResult.Ready(
            date = date,
            totalDiners = entry.totalDiners,
            totalFirsts = firsts.sumOf { it.total },
            totalSeconds = seconds.sumOf { it.total },
            firsts = firsts,
            seconds = seconds,
        )
    }

    private fun group(
        entry: DailyEntry,
        pick: (com.menufogon.core.model.EntryLine) -> Pair<String?, String>,
    ): List<DishPrep> {
        data class Acc(val name: String, val allergens: LinkedHashSet<String>, val sources: MutableList<DishSource>)
        val byDish = LinkedHashMap<String, Acc>()
        for (line in entry.lines) {
            if (line.count <= 0) continue
            val (name, allergens) = pick(line)
            if (name.isNullOrBlank()) continue
            val acc = byDish.getOrPut(TextNormalizer.normalize(name)) { Acc(name, LinkedHashSet(), mutableListOf()) }
            if (allergens.isNotBlank()) acc.allergens += allergens.trim()
            acc.sources += DishSource(line.typeId, line.typeName, line.count)
        }
        return byDish.values
            .map { DishPrep(it.name, it.allergens.joinToString(" · "), it.sources.sumOf { s -> s.count }, it.sources) }
            .sortedByDescending { it.total }
    }
}
