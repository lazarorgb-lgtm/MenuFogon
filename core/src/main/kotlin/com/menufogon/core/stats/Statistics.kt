package com.menufogon.core.stats

import com.menufogon.core.model.DailyEntry
import com.menufogon.core.model.EntryLine
import com.menufogon.core.text.TextNormalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** A statistics period: ISO week (Monday to Sunday), calendar month or calendar year. */
sealed interface Period {
    val start: LocalDate
    val end: LocalDate
    fun previous(): Period
    fun next(): Period
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    data class Week(val weekBasedYear: Int, val week: Int) : Period {
        override val start: LocalDate = LocalDate.of(weekBasedYear, 1, 4)
            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        override val end: LocalDate = start.plusDays(6)
        override fun previous(): Period = of(start.minusWeeks(1))
        override fun next(): Period = of(start.plusWeeks(1))

        companion object {
            fun of(date: LocalDate) = Week(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        }
    }

    data class Month(val year: Int, val month: Int) : Period {
        private val ym = YearMonth.of(year, month)
        override val start: LocalDate = ym.atDay(1)
        override val end: LocalDate = ym.atEndOfMonth()
        override fun previous(): Period = ym.minusMonths(1).let { Month(it.year, it.monthValue) }
        override fun next(): Period = ym.plusMonths(1).let { Month(it.year, it.monthValue) }

        companion object {
            fun of(date: LocalDate) = Month(date.year, date.monthValue)
        }
    }

    data class YearPeriod(val year: Int) : Period {
        override val start: LocalDate = LocalDate.of(year, 1, 1)
        override val end: LocalDate = LocalDate.of(year, 12, 31)
        val lengthInDays: Int get() = Year.of(year).length()
        override fun previous(): Period = YearPeriod(year - 1)
        override fun next(): Period = YearPeriod(year + 1)

        companion object {
            fun of(date: LocalDate) = YearPeriod(date.year)
        }
    }
}

data class TypeShare(val typeId: Int, val typeName: String, val count: Int, val percent: Double)
data class DishCount(val name: String, val count: Int)
data class DayTotal(val date: LocalDate, val diners: Int)

/** One bar of the chart: a day (week, month) or a month (year). */
data class Bar(val start: LocalDate, val value: Int)

data class Statistics(
    val period: Period,
    val daysWithService: Int,
    val totalDiners: Int,
    /** Average per day with service; 0 when there is none. */
    val dailyAverage: Double,
    val byType: List<TypeShare>,
    val firsts: List<DishCount>,
    val seconds: List<DishCount>,
    val busiestDay: DayTotal?,
    val quietestDay: DayTotal?,
    val previousTotal: Int,
    /** Percentage change against the previous period; null when the previous total is 0. */
    val variationPercent: Double?,
    val bars: List<Bar>,
) {
    val hasData: Boolean get() = daysWithService > 0
}

object StatisticsCalculator {

    /**
     * @param entries all saved entries (any date); only the ones inside the period and the
     *   previous period are used. Dish and type names come from the copy stored in each entry.
     * @param typeNames current names by type id, used instead of the stored name when present.
     */
    fun calculate(period: Period, entries: List<DailyEntry>, typeNames: Map<Int, String> = emptyMap()): Statistics {
        val inPeriod = entries.filter { it.date in period }.sortedBy { it.date }
        val served = inPeriod.filter { it.totalDiners > 0 }
        val total = served.sumOf { it.totalDiners }
        val previous = period.previous()
        val previousTotal = entries.filter { it.date in previous }.sumOf { it.totalDiners }

        val byType = served.flatMap { it.lines }
            .filter { it.count > 0 }
            .groupBy { it.typeId }
            .map { (id, lines) ->
                val count = lines.sumOf { it.count }
                TypeShare(id, typeNames[id] ?: lines.last().typeName, count, if (total > 0) count * 100.0 / total else 0.0)
            }
            .sortedBy { it.typeId }

        val dayTotals = served.map { DayTotal(it.date, it.totalDiners) }

        return Statistics(
            period = period,
            daysWithService = served.size,
            totalDiners = total,
            dailyAverage = if (served.isEmpty()) 0.0 else total.toDouble() / served.size,
            byType = byType,
            firsts = rank(served) { it.firstName },
            seconds = rank(served) { it.secondName },
            busiestDay = dayTotals.maxByOrNull { it.diners },
            quietestDay = dayTotals.minByOrNull { it.diners },
            previousTotal = previousTotal,
            variationPercent = if (previousTotal > 0) (total - previousTotal) * 100.0 / previousTotal else null,
            bars = bars(period, inPeriod),
        )
    }

    private fun rank(entries: List<DailyEntry>, pick: (EntryLine) -> String?): List<DishCount> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (line in entries.flatMap { it.lines }) {
            val name = pick(line)
            if (line.count <= 0 || name.isNullOrBlank()) continue
            val key = TextNormalizer.normalize(name)
            val current = counts[key]
            counts[key] = (current?.first ?: name) to ((current?.second ?: 0) + line.count)
        }
        return counts.values.map { DishCount(it.first, it.second) }
            .sortedWith(compareByDescending<DishCount> { it.count }.thenBy { it.name })
    }

    private fun bars(period: Period, entries: List<DailyEntry>): List<Bar> {
        val byDate = entries.associate { it.date to it.totalDiners }
        return when (period) {
            is Period.YearPeriod -> (1..12).map { m ->
                val ym = YearMonth.of(period.year, m)
                Bar(ym.atDay(1), entries.filter { YearMonth.from(it.date) == ym }.sumOf { it.totalDiners })
            }
            else -> generateSequence(period.start) { it.plusDays(1) }
                .takeWhile { !it.isAfter(period.end) }
                .map { Bar(it, byDate[it] ?: 0) }
                .toList()
        }
    }
}
