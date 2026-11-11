package com.menufogon.core

import com.menufogon.core.model.DailyEntry
import com.menufogon.core.model.EntryLine
import com.menufogon.core.model.MenuType
import com.menufogon.core.prep.PreparationCalculator
import com.menufogon.core.prep.PreparationResult
import com.menufogon.core.stats.Period
import com.menufogon.core.stats.StatisticsCalculator
import com.menufogon.core.validation.QuantityResult
import com.menufogon.core.validation.QuantityValidator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalculationsTest {

    private fun entry(date: String, normal: Int, diabetic: Int = 0, eei: Int = 0) = DailyEntry(
        LocalDate.parse(date),
        listOf(
            EntryLine(MenuType.NORMAL, "Normal", normal, "Salpicón de marisco", "PESCADO", "Pollo a la jardinera", ""),
            EntryLine(MenuType.DIABETIC, "Diabético", diabetic, "Ensalada", "", "Pollo a la plancha", ""),
            EntryLine(MenuType.EEI, "EEI", eei, null, "", "Pollo a la jardinera.", "SULFITOS"),
        ),
    )

    // ---- preparation -------------------------------------------------------

    @Test
    fun dishRepeatedAcrossTypesIsAdded() {
        val date = LocalDate.of(2026, 11, 2)
        val result = PreparationCalculator.calculate(date, entry("2026-11-02", normal = 8, diabetic = 3, eei = 7))
        assertIs<PreparationResult.Ready>(result)
        assertEquals(18, result.totalDiners)
        assertEquals(11, result.totalFirsts) // EEI has no first course
        assertEquals(18, result.totalSeconds)
        val chicken = result.seconds.first()
        assertEquals("Pollo a la jardinera: 15 (Normal 8, EEI 7)", chicken.describe())
        assertEquals("SULFITOS", chicken.allergens)
        assertEquals(2, result.seconds.size)
    }

    @Test
    fun typesWithZeroAreLeftOut() {
        val result = PreparationCalculator.calculate(LocalDate.of(2026, 11, 2), entry("2026-11-02", normal = 5))
        assertIs<PreparationResult.Ready>(result)
        assertEquals(listOf("Salpicón de marisco"), result.firsts.map { it.name })
        assertEquals(1, result.seconds.single().sources.size)
    }

    @Test
    fun dateWithoutEntryIsReportedNotZero() {
        val date = LocalDate.of(2026, 11, 3)
        assertEquals(PreparationResult.NoEntry(date), PreparationCalculator.calculate(date, null))
    }

    // ---- statistics --------------------------------------------------------

    @Test
    fun isoWeekCrossingTheYear() {
        // ISO week 53 of 2026 runs from Mon 28 Dec 2026 to Sun 3 Jan 2027.
        val week = Period.Week.of(LocalDate.of(2027, 1, 1))
        assertEquals(Period.Week(2026, 53), week)
        assertEquals(LocalDate.of(2026, 12, 28), week.start)
        assertEquals(LocalDate.of(2027, 1, 3), week.end)
        assertEquals(Period.Week(2027, 1), week.next())
        assertEquals(Period.Week(2026, 52), week.previous())

        val entries = listOf(
            entry("2026-12-27", normal = 100), // previous week
            entry("2026-12-31", normal = 10),
            entry("2027-01-02", normal = 20, eei = 10),
            entry("2027-01-04", normal = 99), // next week
        )
        val stats = StatisticsCalculator.calculate(week, entries)
        assertEquals(2, stats.daysWithService)
        assertEquals(40, stats.totalDiners)
        assertEquals(20.0, stats.dailyAverage)
        assertEquals(7, stats.bars.size)
        assertEquals(100, stats.previousTotal)
        assertEquals(-60.0, stats.variationPercent)
        assertEquals(LocalDate.of(2027, 1, 2), stats.busiestDay!!.date)
        assertEquals(LocalDate.of(2026, 12, 31), stats.quietestDay!!.date)
    }

    @Test
    fun weekOneCanStartInPreviousYear() {
        // 1 Jan 2026 is a Thursday: ISO week 1 of 2026 starts on Mon 29 Dec 2025.
        val week = Period.Week.of(LocalDate.of(2025, 12, 30))
        assertEquals(Period.Week(2026, 1), week)
        assertEquals(LocalDate.of(2025, 12, 29), week.start)
    }

    @Test
    fun monthLengths() {
        assertEquals(28, StatisticsCalculator.calculate(Period.Month(2026, 2), emptyList()).bars.size)
        assertEquals(29, StatisticsCalculator.calculate(Period.Month(2028, 2), emptyList()).bars.size)
        assertEquals(30, StatisticsCalculator.calculate(Period.Month(2026, 11), emptyList()).bars.size)
        assertEquals(31, StatisticsCalculator.calculate(Period.Month(2026, 12), emptyList()).bars.size)
        assertEquals(Period.Month(2027, 1), Period.Month(2026, 12).next())
        assertEquals(Period.Month(2025, 12), Period.Month(2026, 1).previous())
    }

    @Test
    fun leapYear() {
        assertEquals(366, Period.YearPeriod(2028).lengthInDays)
        assertEquals(365, Period.YearPeriod(2026).lengthInDays)
        val entries = listOf(entry("2028-02-29", normal = 12), entry("2028-03-01", normal = 5), entry("2027-02-28", normal = 4))
        val stats = StatisticsCalculator.calculate(Period.YearPeriod(2028), entries)
        assertEquals(12, stats.bars.size)
        assertEquals(12, stats.bars[1].value)
        assertEquals(5, stats.bars[2].value)
        assertEquals(17, stats.totalDiners)
        assertEquals(4, stats.previousTotal)
        assertEquals(12, StatisticsCalculator.calculate(Period.Month(2028, 2), entries).totalDiners)
    }

    @Test
    fun sharesRankingAndEmptyPeriod() {
        val entries = listOf(entry("2026-11-02", normal = 6, diabetic = 2, eei = 2), entry("2026-11-03", normal = 0))
        val stats = StatisticsCalculator.calculate(Period.Month(2026, 11), entries, mapOf(MenuType.NORMAL to "Basal"))
        assertEquals(1, stats.daysWithService) // a saved day with 0 diners is not a service day
        assertEquals(listOf("Basal", "Diabético", "EEI"), stats.byType.map { it.typeName })
        assertEquals(listOf(60.0, 20.0, 20.0), stats.byType.map { it.percent })
        assertEquals("Pollo a la jardinera", stats.seconds.first().name)
        assertEquals(8, stats.seconds.first().count)
        assertEquals(listOf(6, 2), stats.firsts.map { it.count })
        assertNull(stats.variationPercent)

        val empty = StatisticsCalculator.calculate(Period.Month(2026, 10), entries)
        assertFalse(empty.hasData)
        assertEquals(0.0, empty.dailyAverage)
        assertNull(empty.busiestDay)
    }

    // ---- quantities --------------------------------------------------------

    @Test
    fun quantities() {
        assertEquals(QuantityResult.Valid(0), QuantityValidator.parse(""))
        assertEquals(QuantityResult.Valid(0), QuantityValidator.parse("0"))
        assertEquals(QuantityResult.Valid(9999), QuantityValidator.parse(" 9999 "))
        assertEquals(QuantityResult.OutOfRange, QuantityValidator.parse("10000"))
        assertEquals(QuantityResult.OutOfRange, QuantityValidator.parse("99999999999999"))
        assertEquals(QuantityResult.NotANumber, QuantityValidator.parse("-1"))
        assertEquals(QuantityResult.NotANumber, QuantityValidator.parse("3,5"))
        assertEquals(QuantityResult.NotANumber, QuantityValidator.parse("doce"))
        assertTrue(QuantityValidator.isValid(0))
        assertFalse(QuantityValidator.isValid(-1))
        assertFalse(QuantityValidator.isValid(10000))
    }
}
