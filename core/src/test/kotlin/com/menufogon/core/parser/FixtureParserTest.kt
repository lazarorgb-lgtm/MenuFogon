package com.menufogon.core.parser

import com.menufogon.core.text.TextItem
import com.menufogon.core.text.TextNormalizer.normalize
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Acceptance checks against the text positions extracted from the real November 2026 PDFs. */
class FixtureParserTest {

    private val november = YearMonth.of(2026, 11)

    private fun load(name: String): ParseResult {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
            ?: error("Fixture $name not found. Run ./gradlew :tools:run to generate it.")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val items = Json.decodeFromString(ListSerializer(TextItem.serializer()), text)
        return MenuParser().parse(items, november)
    }

    private val normal by lazy { load("MENU_NORMAL_NOVIEMBRE_26.json") }
    private val diabetic by lazy { load("MENU_DIB_NOVIEMBRE_26.json") }
    private val eei by lazy { load("MENU_EEI_NOVIEMBRE.json") }

    private fun assertDish(expected: String, actual: ParsedCourse?) {
        assertNotNull(actual, "expected dish '$expected'")
        assertEquals(normalize(expected), normalize(actual.name))
    }

    @Test
    fun normalDay1() {
        val day = normal.day(1)!!
        assertDish("Espárragos blancos con huevo cocido", day.first)
        assertDish("Merluza a la romana con verduras", day.second)
        assertEquals("PESCADO, GLUTEN Y HUEVO", day.second!!.allergens)
    }

    @Test
    fun normalDay2() {
        val day = normal.day(2)!!
        assertDish("Salpicón de marisco", day.first)
        assertDish("Pollo a la jardinera", day.second)
        assertEquals("CRUSTÁCEOS, MOLUSCOS Y PESCADO", day.first!!.allergens)
    }

    @Test
    fun eeiDay2IsHolidayWithoutFirst() {
        val day = eei.day(2)!!
        assertNull(day.first)
        assertTrue(day.noFirst)
        assertDish("Pollo a la jardinera", day.second)
        assertFalse(DayIssue.MISSING_FIRST in day.issues)
    }

    @Test
    fun diabeticDay13Second() {
        assertDish("Tortilla de atún, guisantes y pimiento rojo", diabetic.day(13)!!.second)
    }

    @Test
    fun normalDay30() {
        val day = normal.day(30)!!
        assertDish("Sopa de cocido", day.first)
        assertDish("Cocido completo", day.second)
    }

    @Test
    fun dayCountsPerType() {
        assertEquals(30, normal.days.size)
        assertEquals(30, diabetic.days.size)
        assertEquals(21, eei.days.size)
    }

    @Test
    fun eeiHasNoWeekends() {
        assertTrue(eei.days.none { it.date.dayOfWeek == DayOfWeek.SATURDAY || it.date.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test
    fun everyDayHasBothCoursesExceptHoliday() {
        for (result in listOf(normal, diabetic, eei)) {
            for (day in result.days) {
                assertNotNull(day.second, "second of ${day.date}")
                if (!day.noFirst) assertNotNull(day.first, "first of ${day.date}")
                assertFalse(DayIssue.WEEKDAY_MISMATCH in day.issues, "weekday of ${day.date}")
            }
        }
        assertEquals(1, eei.days.count { it.noFirst })
        assertEquals(0, normal.days.count { it.noFirst })
    }

    @Test
    fun typosAreShownAsWritten() {
        // The original says "Esparragos" (no accent) and "pimiento rojo.." (double dot).
        assertEquals("Esparragos blancos con huevo cocido", normal.day(1)!!.first!!.name)
        assertEquals("Tortilla de atún, guisantes y pimiento rojo", normal.day(13)!!.second!!.name)
    }

    @Test
    fun footerAndTitlesAreNotDishes() {
        for (result in listOf(normal, diabetic, eei)) {
            for (day in result.days) {
                for (course in listOfNotNull(day.first, day.second)) {
                    val n = normalize(course.name + " " + course.ingredients + " " + course.allergens)
                    assertFalse("reglamento" in n, "footer leaked into ${day.date}")
                    assertFalse("noviembre" in n, "title leaked into ${day.date}")
                }
            }
        }
    }
}
