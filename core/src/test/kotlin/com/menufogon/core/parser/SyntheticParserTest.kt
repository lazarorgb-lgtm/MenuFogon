package com.menufogon.core.parser

import com.menufogon.core.model.MenuType
import com.menufogon.core.text.TextItem
import com.menufogon.core.text.TextNormalizer
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parser tests with hand-made text, word by word, as the extractors produce it. */
class SyntheticParserTest {

    private val november = YearMonth.of(2026, 11)
    private val items = mutableListOf<TextItem>()

    /** Adds a line of text starting at [x], one item per word, 6 units per character. */
    private fun line(text: String, x: Float, y: Float, height: Float = 11f, page: Int = 0) {
        var cursor = x
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val width = word.length * 6f
            items += TextItem(word, cursor, y, page, width, height)
            cursor += width + 6f
        }
    }

    private fun parse() = MenuParser().parse(items.shuffled(java.util.Random(7)), november)

    // Column left edges; each column is 140 wide.
    private val mon = 30f
    private val tue = 170f
    private val sun = 870f

    @Test
    fun sundayHeaderAboveFirstWeekRow() {
        line("DOMINGO 1", sun + 30, 100f)
        line("Ensalada. (Lechuga y tomate.)", sun, 115f)
        line("Pollo asado. (Pollo y sal.)", sun, 160f)
        line("LUNES 2", mon + 30, 200f)
        line("DOMINGO 8", sun + 30, 200f)
        line("Sopa. (Caldo y fideos.)", mon, 215f)
        line("Arroz blanco. (Arroz y sal.)", sun, 215f)
        line("Filete. (Ternera y sal.)", mon, 260f)
        line("Merluza. (Merluza y sal.)", sun, 260f)

        val result = parse()
        assertEquals(listOf(1, 2, 8), result.days.map { it.date.dayOfMonth })
        assertEquals("Ensalada", result.day(1)!!.first!!.name)
        assertEquals("Pollo asado", result.day(1)!!.second!!.name)
        assertEquals("Arroz blanco", result.day(8)!!.first!!.name)
        assertEquals("Merluza", result.day(8)!!.second!!.name)
        assertEquals("Sopa", result.day(2)!!.first!!.name)
    }

    @Test
    fun allergensSplitInTwoLines() {
        line("LUNES 2", mon + 30, 200f)
        line("Salpicón de marisco. (Potón,", mon, 215f)
        line("gambas y sal.)", mon, 228f)
        line("CRUSTÁCEOS, MOLUSCOS Y", mon, 243f, height = 7f)
        line("PESCADO", mon + 20, 252f, height = 7f)
        line("Pollo. (Pollo y ajo.)", mon, 280f)
        line("SULFITOS", mon, 295f, height = 7f)

        val day = parse().day(2)!!
        assertEquals("CRUSTÁCEOS, MOLUSCOS Y PESCADO", day.first!!.allergens)
        assertEquals("Potón, gambas y sal", day.first!!.ingredients)
        assertEquals("SULFITOS", day.second!!.allergens)
        assertTrue(day.issues.isEmpty())
    }

    @Test
    fun dishWithoutIngredients() {
        line("LUNES 2", mon + 30, 200f)
        line("Fruta del tiempo", mon, 215f)
        line("Yogur natural.", mon, 260f)
        line("LACTOSA", mon, 275f, height = 7f)

        val day = parse().day(2)!!
        assertEquals("Fruta del tiempo", day.first!!.name)
        assertEquals("", day.first!!.ingredients)
        assertEquals("Yogur natural", day.second!!.name)
        assertEquals("LACTOSA", day.second!!.allergens)
    }

    @Test
    fun typosAndDoubleDots() {
        line("LUNES 2", mon + 30, 200f)
        line("MARTES 3", tue + 30, 200f)
        line("Esparragos con jamón", mon, 215f)
        line("serrano.. (Esparragos", mon, 228f)
        line("y jamon.)", mon, 241f)
        line("Brocoli con pimenton.", tue, 215f)
        line("Judias verdes.. (Sal.)", mon, 290f)
        line("Bienmesabe (cazón adobado).", tue, 290f)
        line("(Cazón, vinagre y sal.)", tue, 303f)

        val result = parse()
        val monday = result.day(2)!!
        // Shown as written, compared without accents or extra signs.
        assertEquals("Esparragos con jamón serrano", monday.first!!.name)
        assertEquals(TextNormalizer.normalize("Espárragos con jamón serrano"), TextNormalizer.normalize(monday.first!!.name))
        assertEquals("Judias verdes", monday.second!!.name)
        val tuesday = result.day(3)!!
        assertEquals("Brocoli con pimenton", tuesday.first!!.name)
        assertEquals("Bienmesabe (cazón adobado)", tuesday.second!!.name)
        assertEquals("Cazón, vinagre y sal", tuesday.second!!.ingredients)
    }

    @Test
    fun holidayMeansNoFirstCourse() {
        line("LUNES 2", mon + 30, 200f)
        line("FESTIVO", mon + 30, 240f)
        line("Pollo a la jardinera. (Pollo.)", mon, 300f)

        val day = parse().day(2)!!
        assertNull(day.first)
        assertTrue(day.noFirst)
        assertEquals("Pollo a la jardinera", day.second!!.name)
        assertTrue(day.issues.isEmpty())
    }

    @Test
    fun lastDayAloneOnSecondPageAndFooterIgnored() {
        line("LUNES 23", mon + 30, 60f, page = 1)
        line("MARTES 24", tue + 30, 60f, page = 1)
        line("Lentejas. (Lentejas.)", mon, 75f, page = 1)
        line("Repollo. (Repollo.)", tue, 75f, page = 1)
        line("Tortilla. (Huevo.)", mon, 120f, page = 1)
        line("Pollo. (Pollo.)", tue, 120f, page = 1)
        line("LUNES 30", mon + 30, 160f, page = 1)
        line("Sopa de cocido. (Caldo.)", mon, 175f, page = 1)
        line("GLUTEN", mon, 188f, height = 7f, page = 1)
        line("Cocido completo. (Garbanzos.)", mon, 220f, page = 1)
        line("Alérgenos declarados conforme al Reglamento.", tue, 280f, page = 1)

        val result = parse()
        assertEquals("Lentejas", result.day(23)!!.first!!.name)
        assertEquals("Tortilla", result.day(23)!!.second!!.name)
        assertEquals("Sopa de cocido", result.day(30)!!.first!!.name)
        assertEquals("GLUTEN", result.day(30)!!.first!!.allergens)
        assertEquals("Cocido completo", result.day(30)!!.second!!.name)
        assertEquals("Pollo", result.day(24)!!.second!!.name)
    }

    @Test
    fun missingDishesAreFlagged() {
        line("LUNES 2", mon + 30, 200f)
        line("MARTES 3", tue + 30, 200f)
        line("Sopa. (Caldo.)", mon, 215f)

        val result = parse()
        assertEquals(setOf(DayIssue.MISSING_SECOND), result.day(2)!!.issues)
        assertEquals(setOf(DayIssue.MISSING_FIRST, DayIssue.MISSING_SECOND), result.day(3)!!.issues)
    }

    @Test
    fun wrongWeekdayAndImpossibleDay() {
        line("MARTES 2", mon + 30, 200f) // 2 Nov 2026 is a Monday
        line("Sopa. (Caldo.)", mon, 215f)
        line("Filete. (Ternera.)", mon, 260f)
        line("LUNES 31", tue + 30, 200f) // November has 30 days

        val result = parse()
        assertTrue(DayIssue.WEEKDAY_MISMATCH in result.day(2)!!.issues)
        assertNull(result.day(31))
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun textWithoutHeadersGivesEmptyResult() {
        line("Texto cualquiera sin rejilla", 30f, 100f)
        val result = parse()
        assertTrue(result.isEmpty)
        assertNotNull(result.warnings.firstOrNull())
    }

    @Test
    fun typeIsSuggestedFromFileNameOnly() {
        assertEquals(MenuType.NORMAL, MenuParser.suggestTypeId("MENU_NORMAL_NOVIEMBRE_26.pdf"))
        assertEquals(MenuType.DIABETIC, MenuParser.suggestTypeId("MENU DIB NOVIEMBRE 26.pdf"))
        assertEquals(MenuType.EEI, MenuParser.suggestTypeId("MENU_EEI_NOVIEMBRE.pdf"))
        assertNull(MenuParser.suggestTypeId("documento.pdf"))
    }
}
