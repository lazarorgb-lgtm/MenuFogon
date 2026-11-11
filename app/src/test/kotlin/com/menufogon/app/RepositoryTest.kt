package com.menufogon.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.menufogon.app.data.AppDatabase
import com.menufogon.app.data.DailyEntryEntity
import com.menufogon.app.data.DailyMenuEntity
import com.menufogon.app.data.DishEntity
import com.menufogon.app.data.EntryLineEntity
import com.menufogon.app.data.MenuFileEntity
import com.menufogon.app.data.MenuRepository
import com.menufogon.app.data.MenuTypeEntity
import com.menufogon.app.data.MonthEntity
import com.menufogon.app.data.ReviewedDay
import com.menufogon.app.data.toEntity
import com.menufogon.app.data.toModel
import com.menufogon.core.backup.BackupCodec
import com.menufogon.core.backup.BackupData
import com.menufogon.core.backup.BackupReadResult
import com.menufogon.core.model.Dish
import com.menufogon.core.model.MenuType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MenuRepository
    private val november = YearMonth.of(2026, 11)
    private val nov2: LocalDate = LocalDate.of(2026, 11, 2)
    private val nov3: LocalDate = LocalDate.of(2026, 11, 3)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.SEED)
            .allowMainThreadQueries()
            .setTransactionExecutor(Executors.newSingleThreadExecutor())
            .build()
        repository = MenuRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun loadNormalAndEei() {
        repository.saveMonthMenu(
            november, MenuType.NORMAL, "normal.pdf",
            listOf(
                ReviewedDay(nov2, "Salpicón de marisco.", "Potón, gambas", "CRUSTÁCEOS", "Pollo a la jardinera", "Pollo", ""),
                ReviewedDay(nov3, "Lentejas a la jardinera", "", "", "Croquetas caseras de jamón", "", "GLUTEN Y HUEVO"),
            ),
            nowEpochMs = 1000,
        )
        repository.saveMonthMenu(
            november, MenuType.EEI, "eei.pdf",
            // Holiday: no first course. Same second dish as Normal, written with a typo-style difference.
            listOf(ReviewedDay(nov2, secondName = "Pollo a la jardinera..", secondAllergens = "SULFITOS")),
            nowEpochMs = 2000,
        )
    }

    // ---- DAOs ------------------------------------------------------------------

    @Test
    fun seedCreatesTheThreeFixedTypes() = runBlocking {
        assertEquals(MenuType.DEFAULTS, repository.getTypes())
    }

    @Test
    fun daoStoresAndReadsEveryTable() = runBlocking {
        val dao = db.menuDao()
        dao.insertMonths(listOf(MonthEntity(2026, 11), MonthEntity(2026, 11)))
        assertEquals(listOf(MonthEntity(2026, 11)), dao.getMonths())

        val id = dao.insertDish(DishEntity(0, "Sopa", "sopa", "Caldo", "GLUTEN"))
        assertEquals(id, dao.findDish("sopa")!!.id)
        // The normalised name is unique.
        assertFailsWith<Exception> { dao.insertDish(DishEntity(0, "SOPA", "sopa", "", "")) }

        dao.upsertMenus(listOf(DailyMenuEntity("2026-11-02", MenuType.NORMAL, id, null, "GLUTEN", "")))
        dao.upsertMenus(listOf(DailyMenuEntity("2026-11-02", MenuType.NORMAL, null, id, "", "GLUTEN")))
        val menus = dao.getMenus("2026-11-01", "2026-11-30")
        assertEquals(1, menus.size) // one per date and type
        assertNull(menus.single().firstName)
        assertEquals("Sopa", menus.single().secondName)

        dao.upsertFiles(listOf(MenuFileEntity(2026, 11, MenuType.NORMAL, "a.pdf", 1)))
        dao.upsertFiles(listOf(MenuFileEntity(2026, 11, MenuType.NORMAL, "b.pdf", 2)))
        assertEquals(listOf("b.pdf"), dao.getFiles().map { it.fileName }) // one per month and type

        dao.insertEntries(listOf(DailyEntryEntity("2026-11-02"), DailyEntryEntity("2026-11-02")))
        dao.upsertLines(listOf(EntryLineEntity("2026-11-02", MenuType.NORMAL, "Normal", 8, null, "", "Sopa", "GLUTEN")))
        assertEquals(listOf("2026-11-02"), dao.getEntryDates()) // one entry per date
        dao.deleteEntry("2026-11-02")
        assertTrue(dao.getAllLines().isEmpty()) // lines are deleted with their entry
    }

    // ---- mapping ---------------------------------------------------------------

    @Test
    fun entitiesMapToModelsAndBack() {
        val dish = DishEntity(7, "Pollo", "pollo", "Pollo y sal", "SULFITOS")
        assertEquals(Dish(7, "Pollo", "pollo", "Pollo y sal", "SULFITOS"), dish.toModel())
        assertEquals(dish, dish.toModel().toEntity())

        val menu = DailyMenuEntity("2026-11-02", MenuType.EEI, null, 7, "", "SULFITOS")
        assertEquals(nov2, menu.toModel().date)
        assertEquals(menu, menu.toModel().toEntity())

        val line = EntryLineEntity("2026-11-02", MenuType.EEI, "EEI", 7, null, "", "Pollo", "SULFITOS")
        assertEquals(line, line.toModel().toEntity(nov2))

        val file = MenuFileEntity(2026, 11, MenuType.NORMAL, "normal.pdf", 123)
        assertEquals(file, file.toModel().toEntity())
        assertEquals(MenuTypeEntity(2, "Diabético"), MenuType(2, "Diabético").toEntity())
        assertEquals(MonthEntity(2026, 11), MonthEntity(2026, 11).toModel().toEntity())
    }

    // ---- import of a month -----------------------------------------------------

    @Test
    fun dishesAreReusedBetweenTypesAndShownAsFirstWritten() = runBlocking {
        loadNormalAndEei()
        val dishes = repository.observeDishes().first()
        assertEquals(4, dishes.size)
        assertEquals(1, dishes.count { it.normalizedName == "pollo a la jardinera" })
        assertEquals("Salpicón de marisco", dishes.first { it.normalizedName == "salpicon de marisco" }.displayName)

        val eei = repository.getMenus(nov2).single { it.typeId == MenuType.EEI }
        assertNull(eei.firstName)
        assertEquals("Pollo a la jardinera", eei.secondName)
        assertEquals("SULFITOS", eei.secondAllergens)
    }

    @Test
    fun reimportReplacesOnlyThatTypeAndNeverTheEntries() = runBlocking {
        loadNormalAndEei()
        repository.saveEntry(nov2, mapOf(MenuType.NORMAL to 8, MenuType.EEI to 7))

        repository.saveMonthMenu(
            november, MenuType.NORMAL, "normal-v2.pdf",
            listOf(ReviewedDay(nov2, "Ensalada", "", "", "Merluza", "", "PESCADO")),
            nowEpochMs = 3000,
        )

        val menus = repository.getMenus(november)
        assertEquals(1, menus.count { it.typeId == MenuType.NORMAL }) // 3 Nov is gone for Normal
        assertEquals("Ensalada", menus.single { it.typeId == MenuType.NORMAL }.firstName)
        assertEquals(1, menus.count { it.typeId == MenuType.EEI }) // EEI untouched
        assertEquals("normal-v2.pdf", repository.observeFiles(november).first().single { it.typeId == MenuType.NORMAL }.fileName)

        // The saved entry keeps the dish names that were in force when it was saved.
        val entry = assertNotNull(repository.getEntry(nov2))
        val normal = entry.lines.single { it.typeId == MenuType.NORMAL }
        assertEquals(8, normal.count)
        assertEquals("Salpicón de marisco", normal.firstName)
        assertEquals("Pollo a la jardinera", normal.secondName)
        assertEquals(15, entry.totalDiners)
    }

    // ---- daily entries ---------------------------------------------------------

    @Test
    fun oneEntryPerDateSavingAgainUpdates() = runBlocking {
        loadNormalAndEei()
        repository.saveEntry(nov2, mapOf(MenuType.NORMAL to 8, MenuType.EEI to 7, MenuType.DIABETIC to 5))
        repository.saveEntry(nov2, mapOf(MenuType.NORMAL to 10))

        assertEquals(1, repository.getAllEntries().size)
        val entry = repository.getEntry(nov2)!!
        // Diabético has no menu that day, so it is not stored; EEI defaults to 0.
        assertEquals(listOf(MenuType.NORMAL, MenuType.EEI), entry.lines.map { it.typeId })
        assertEquals(listOf(10, 0), entry.lines.map { it.count })
        assertEquals(setOf(nov2), repository.observeEntryDates(november).first())

        repository.deleteEntry(nov2)
        assertNull(repository.getEntry(nov2))
        assertEquals(0, repository.observeEntryCount().first())
    }

    @Test
    fun quantitiesOutOfRangeAreRejected() = runBlocking {
        loadNormalAndEei()
        assertFailsWith<IllegalArgumentException> { repository.saveEntry(nov2, mapOf(MenuType.NORMAL to 10000)) }
        assertFailsWith<IllegalArgumentException> { repository.saveEntry(nov2, mapOf(MenuType.NORMAL to -1)) }
        assertNull(repository.getEntry(nov2))
    }

    @Test
    fun mergingDishesRepointsMenus() = runBlocking {
        loadNormalAndEei()
        val dishes = repository.observeDishes().first()
        val keep = dishes.first { it.normalizedName == "pollo a la jardinera" }
        val remove = dishes.first { it.normalizedName == "croquetas caseras de jamon" }
        repository.mergeDishes(keepId = keep.id, removeId = remove.id)

        assertEquals(3, repository.observeDishes().first().size)
        assertEquals("Pollo a la jardinera", repository.getMenus(nov3).single().secondName)
    }

    // ---- backup ----------------------------------------------------------------

    @Test
    fun exportThenImportIntoAnotherDatabaseGivesTheSameData() = runBlocking {
        loadNormalAndEei()
        repository.renameType(MenuType.EEI, "Guardería")
        repository.saveEntry(nov2, mapOf(MenuType.NORMAL to 8, MenuType.EEI to 7))
        val exported = repository.exportData()
        val text = BackupCodec.encode(exported, Instant.parse("2026-11-11T10:00:00Z"))

        val decoded = assertIs<BackupReadResult.Ok>(BackupCodec.decode(text))
        assertEquals(1, decoded.summary.months)
        assertEquals(2, decoded.summary.daysWithMenu)
        assertEquals(1, decoded.summary.entries)

        // Import over different data: everything is replaced.
        repository.saveMonthMenu(YearMonth.of(2026, 12), MenuType.DIABETIC, "dic.pdf",
            listOf(ReviewedDay(LocalDate.of(2026, 12, 1), "Otra cosa", "", "", "Y otra", "", "")), 4000)
        repository.deleteEntry(nov2)
        repository.replaceAll(decoded.file.data)

        assertEquals(exported, repository.exportData())
        assertEquals("Guardería", repository.getTypes().single { it.id == MenuType.EEI }.name)
    }

    @Test
    fun importIsATransactionAFailureLeavesEverythingAsItWas() = runBlocking {
        loadNormalAndEei()
        repository.saveEntry(nov2, mapOf(MenuType.NORMAL to 8))
        val before = repository.exportData()

        // Two dishes with the same normalised name break the unique index half way through the import.
        val broken = BackupData(
            months = before.months,
            menuTypes = before.menuTypes,
            dishes = listOf(Dish(1, "Sopa", "sopa"), Dish(2, "SOPA", "sopa")),
        )
        assertFailsWith<Exception> { repository.replaceAll(broken) }

        assertEquals(before, repository.exportData())
    }
}
