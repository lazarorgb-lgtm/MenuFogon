package com.menufogon.core.backup

import com.menufogon.core.model.DailyEntry
import com.menufogon.core.model.DailyMenu
import com.menufogon.core.model.Dish
import com.menufogon.core.model.EntryLine
import com.menufogon.core.model.MenuFile
import com.menufogon.core.model.MenuType
import com.menufogon.core.model.MonthRef
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class BackupTest {

    private val exportedAt = Instant.parse("2026-11-11T09:30:00Z")

    private val sample = BackupData(
        months = listOf(MonthRef(2026, 11)),
        menuTypes = listOf(MenuType(1, "Normal"), MenuType(2, "Diabético"), MenuType(3, "Guardería")),
        menuFiles = listOf(MenuFile(2026, 11, MenuType.NORMAL, "MENU_NORMAL_NOVIEMBRE_26.pdf", 1_794_000_000_000)),
        dishes = listOf(
            Dish(1, "Salpicón de marisco", "salpicon de marisco", "Potón, gambas", "CRUSTÁCEOS"),
            Dish(2, "Pollo a la jardinera", "pollo a la jardinera"),
        ),
        dailyMenus = listOf(
            DailyMenu(LocalDate.of(2026, 11, 2), MenuType.NORMAL, 1, 2, "CRUSTÁCEOS", ""),
            DailyMenu(LocalDate.of(2026, 11, 2), MenuType.EEI, null, 2),
        ),
        dailyEntries = listOf(
            DailyEntry(
                LocalDate.of(2026, 11, 2),
                listOf(
                    EntryLine(MenuType.NORMAL, "Normal", 8, "Salpicón de marisco", "CRUSTÁCEOS", "Pollo a la jardinera", ""),
                    EntryLine(MenuType.EEI, "Guardería", 7, null, "", "Pollo a la jardinera", ""),
                ),
            ),
        ),
    )

    private class FakeStore(var data: BackupData) : BackupTarget {
        var writes = 0
        override fun replaceAll(data: BackupData) {
            this.data = data
            writes++
        }
    }

    @Test
    fun exportThenImportGivesExactlyTheSameData() {
        val text = BackupCodec.encode(sample, exportedAt)
        val result = BackupCodec.decode(text)
        assertIs<BackupReadResult.Ok>(result)
        assertEquals(sample, result.file.data)
        assertEquals(BackupCodec.SCHEMA_VERSION, result.file.schemaVersion)
        assertEquals(BackupSummary(exportedAt, months = 1, daysWithMenu = 1, entries = 1), result.summary)
        // Encoding again gives the same text: nothing is lost on the way.
        assertEquals(text, BackupCodec.encode(result.file.data, exportedAt))
    }

    @Test
    fun importReplacesStoreData() {
        val store = FakeStore(BackupData(menuTypes = MenuType.DEFAULTS))
        val result = BackupImporter.import(BackupCodec.encode(sample, exportedAt), store)
        assertIs<BackupReadResult.Ok>(result)
        assertEquals(sample, store.data)
        assertEquals(1, store.writes)
    }

    @Test
    fun unknownVersionIsRejected() {
        val text = BackupCodec.encode(sample, exportedAt).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val store = FakeStore(sample)
        val result = BackupImporter.import(text, store)
        assertEquals(BackupReadResult.UnsupportedVersion(99), result)
        assertEquals(0, store.writes)
        assertSame(sample, store.data)
    }

    @Test
    fun corruptFilesDoNotChangeTheData() {
        val good = BackupCodec.encode(sample, exportedAt)
        val corrupt = listOf(
            "",
            "esto no es una copia",
            "[1, 2, 3]",
            "{}",
            good.substring(0, good.length / 2), // truncated download
            good.replace("\"2026-11-02\"", "\"2026-13-45\""), // impossible date
            good.replace("\"count\": 8", "\"count\": -8"), // negative quantity
            good.replace("\"count\": 7", "\"count\": 10000"), // above the limit
            good.replace("\"secondDishId\": 2", "\"secondDishId\": 77"), // dish that does not exist
            good.replace("\"dailyEntries\"", "\"otraCosa\""), // unexpected structure
            good.replace("\"id\": 3", "\"id\": 4"), // unknown menu type
        )
        for ((index, text) in corrupt.withIndex()) {
            val store = FakeStore(sample)
            val result = BackupImporter.import(text, store)
            assertIs<BackupReadResult.Invalid>(result, "case $index should be invalid")
            assertEquals(0, store.writes, "case $index wrote data")
            assertSame(sample, store.data)
        }
    }

    @Test
    fun fileName() {
        assertEquals("MenuCal-copia-20261111.json", BackupCodec.fileName(LocalDate.of(2026, 11, 11)))
    }
}
