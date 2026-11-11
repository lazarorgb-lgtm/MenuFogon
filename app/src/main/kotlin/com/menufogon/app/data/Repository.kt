package com.menufogon.app.data

import androidx.room.withTransaction
import com.menufogon.core.backup.BackupData
import com.menufogon.core.model.DailyEntry
import com.menufogon.core.model.DailyMenu
import com.menufogon.core.model.Dish
import com.menufogon.core.model.EntryLine
import com.menufogon.core.model.MenuFile
import com.menufogon.core.model.MenuType
import com.menufogon.core.model.MonthRef
import com.menufogon.core.text.TextNormalizer
import com.menufogon.core.validation.QuantityValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth

// ---- entity <-> model mapping ------------------------------------------------

fun MenuTypeEntity.toModel() = MenuType(id, name)
fun MenuType.toEntity() = MenuTypeEntity(id, name)
fun MonthEntity.toModel() = MonthRef(year, month)
fun MonthRef.toEntity() = MonthEntity(year, month)
fun MenuFileEntity.toModel() = MenuFile(year, month, typeId, fileName, importedAtEpochMs)
fun MenuFile.toEntity() = MenuFileEntity(year, month, typeId, fileName, importedAtEpochMs)
fun DishEntity.toModel() = Dish(id, displayName, normalizedName, ingredients, allergens)
fun Dish.toEntity() = DishEntity(id, displayName, normalizedName, ingredients, allergens)
fun DailyMenuEntity.toModel() =
    DailyMenu(LocalDate.parse(date), typeId, firstDishId, secondDishId, firstAllergens, secondAllergens)
fun DailyMenu.toEntity() =
    DailyMenuEntity(date.toString(), typeId, firstDishId, secondDishId, firstAllergens, secondAllergens)
fun EntryLineEntity.toModel() =
    EntryLine(typeId, typeName, count, firstName, firstAllergens, secondName, secondAllergens)
fun EntryLine.toEntity(date: LocalDate) =
    EntryLineEntity(date.toString(), typeId, typeName, count, firstName, firstAllergens, secondName, secondAllergens)

/** Published menu of one type on one day, with dish names resolved. */
data class DayMenu(
    val date: LocalDate,
    val typeId: Int,
    val firstName: String?,
    val firstAllergens: String,
    val secondName: String?,
    val secondAllergens: String,
)

fun MenuWithDishes.toDayMenu() =
    DayMenu(LocalDate.parse(date), typeId, firstName, firstAllergens, secondName, secondAllergens)

/** One reviewed day of an import, as confirmed by the user. Blank names mean "no dish". */
data class ReviewedDay(
    val date: LocalDate,
    val firstName: String = "",
    val firstIngredients: String = "",
    val firstAllergens: String = "",
    val secondName: String = "",
    val secondIngredients: String = "",
    val secondAllergens: String = "",
)

class MenuRepository(private val db: AppDatabase) {
    private val dao = db.menuDao()

    // ---- types ---------------------------------------------------------------

    fun observeTypes(): Flow<List<MenuType>> = dao.observeTypes().map { list -> list.map { it.toModel() } }

    suspend fun getTypes(): List<MenuType> = dao.getTypes().map { it.toModel() }

    suspend fun renameType(id: Int, name: String) {
        val clean = name.trim()
        if (id in MenuType.IDS && clean.isNotEmpty()) dao.upsertTypes(listOf(MenuTypeEntity(id, clean)))
    }

    // ---- months and menus ----------------------------------------------------

    fun observeFiles(month: YearMonth): Flow<List<MenuFile>> =
        dao.observeFiles(month.year, month.monthValue).map { list -> list.map { it.toModel() } }

    fun observeMenus(month: YearMonth): Flow<List<DayMenu>> =
        dao.observeMenus(month.atDay(1).toString(), month.atEndOfMonth().toString())
            .map { list -> list.map { it.toDayMenu() } }

    suspend fun getMenus(date: LocalDate): List<DayMenu> =
        dao.getMenus(date.toString(), date.toString()).map { it.toDayMenu() }

    suspend fun getMenus(month: YearMonth): List<DayMenu> =
        dao.getMenus(month.atDay(1).toString(), month.atEndOfMonth().toString()).map { it.toDayMenu() }

    /**
     * Saves the reviewed menu of one type for one month. Replaces only that type and month
     * and never touches the daily entries.
     */
    suspend fun saveMonthMenu(month: YearMonth, typeId: Int, fileName: String, days: List<ReviewedDay>, nowEpochMs: Long) {
        require(typeId in MenuType.IDS)
        db.withTransaction {
            dao.insertMonths(listOf(MonthEntity(month.year, month.monthValue)))
            dao.deleteMenus(typeId, month.atDay(1).toString(), month.atEndOfMonth().toString())
            val menus = days
                .filter { YearMonth.from(it.date) == month }
                .filter { it.firstName.isNotBlank() || it.secondName.isNotBlank() }
                .map { day ->
                    DailyMenuEntity(
                        date = day.date.toString(),
                        typeId = typeId,
                        firstDishId = dishId(day.firstName, day.firstIngredients, day.firstAllergens),
                        secondDishId = dishId(day.secondName, day.secondIngredients, day.secondAllergens),
                        firstAllergens = if (day.firstName.isBlank()) "" else day.firstAllergens.trim(),
                        secondAllergens = if (day.secondName.isBlank()) "" else day.secondAllergens.trim(),
                    )
                }
            dao.upsertMenus(menus)
            dao.upsertFiles(listOf(MenuFileEntity(month.year, month.monthValue, typeId, fileName, nowEpochMs)))
        }
    }

    /** Finds the dish by its normalised name or creates it. Must run inside a transaction. */
    private suspend fun dishId(name: String, ingredients: String, allergens: String): Long? {
        val display = TextNormalizer.cleanDisplay(name)
        val normalized = TextNormalizer.normalize(display)
        if (normalized.isEmpty()) return null
        val existing = dao.findDish(normalized)
        if (existing == null) {
            return dao.insertDish(DishEntity(0, display, normalized, ingredients.trim(), allergens.trim()))
        }
        // Complete missing details but keep the name the user already sees.
        val completed = existing.copy(
            ingredients = existing.ingredients.ifBlank { ingredients.trim() },
            allergens = existing.allergens.ifBlank { allergens.trim() },
        )
        if (completed != existing) dao.updateDish(completed)
        return existing.id
    }

    // ---- dishes --------------------------------------------------------------

    fun observeDishes(): Flow<List<Dish>> = dao.observeDishes().map { list -> list.map { it.toModel() } }

    /** Merges [removeId] into [keepId]: menus that used the removed dish now use the kept one. */
    suspend fun mergeDishes(keepId: Long, removeId: Long) {
        if (keepId == removeId) return
        db.withTransaction {
            val all = dao.getDishes().associateBy { it.id }
            val keep = all[keepId] ?: return@withTransaction
            val remove = all[removeId] ?: return@withTransaction
            dao.repointFirst(removeId, keepId)
            dao.repointSecond(removeId, keepId)
            dao.deleteDish(removeId)
            dao.updateDish(
                keep.copy(
                    ingredients = keep.ingredients.ifBlank { remove.ingredients },
                    allergens = keep.allergens.ifBlank { remove.allergens },
                ),
            )
        }
    }

    // ---- daily entries -------------------------------------------------------

    fun observeEntryDates(month: YearMonth): Flow<Set<LocalDate>> =
        dao.observeEntryDates(month.atDay(1).toString(), month.atEndOfMonth().toString())
            .map { list -> list.map(LocalDate::parse).toSet() }

    fun observeEntryCount(): Flow<Int> = dao.observeEntryCount()

    suspend fun getEntry(date: LocalDate): DailyEntry? {
        if (!dao.hasEntry(date.toString())) return null
        return DailyEntry(date, dao.getLines(date.toString()).map { it.toModel() })
    }

    suspend fun getAllEntries(): List<DailyEntry> {
        val lines = dao.getAllLines().groupBy { it.date }
        return dao.getEntryDates().map { date ->
            DailyEntry(LocalDate.parse(date), lines[date].orEmpty().map { it.toModel() })
        }
    }

    /**
     * Saves (or replaces) the entry of a date. The dish names in force that day are copied
     * into the entry, so later imports do not change the history.
     * Only types with a published menu that day are stored.
     */
    suspend fun saveEntry(date: LocalDate, counts: Map<Int, Int>) {
        require(counts.values.all(QuantityValidator::isValid)) { "quantity out of range" }
        db.withTransaction {
            val names = dao.getTypes().associate { it.id to it.name }
            val lines = dao.getMenus(date.toString(), date.toString()).map { menu ->
                EntryLineEntity(
                    date = date.toString(),
                    typeId = menu.typeId,
                    typeName = names[menu.typeId].orEmpty(),
                    count = counts[menu.typeId] ?: 0,
                    firstName = menu.firstName,
                    firstAllergens = menu.firstAllergens,
                    secondName = menu.secondName,
                    secondAllergens = menu.secondAllergens,
                )
            }
            dao.insertEntries(listOf(DailyEntryEntity(date.toString())))
            dao.deleteLines(date.toString())
            dao.upsertLines(lines)
        }
    }

    suspend fun deleteEntry(date: LocalDate) = dao.deleteEntry(date.toString())

    // ---- backup --------------------------------------------------------------

    suspend fun exportData(): BackupData = db.withTransaction {
        val lines = dao.getAllLines().groupBy { it.date }
        BackupData(
            months = dao.getMonths().map { it.toModel() },
            menuTypes = dao.getTypes().map { it.toModel() },
            menuFiles = dao.getFiles().map { it.toModel() },
            dishes = dao.getDishes().map { it.toModel() },
            dailyMenus = dao.getAllMenus().map { it.toModel() },
            dailyEntries = dao.getEntryDates().map { date ->
                DailyEntry(LocalDate.parse(date), lines[date].orEmpty().map { it.toModel() })
            },
        )
    }

    /**
     * Replaces everything with [data] in one transaction: it is applied completely or not at all.
     * [data] must already be validated with BackupCodec.
     */
    suspend fun replaceAll(data: BackupData) {
        db.withTransaction {
            dao.clearLines()
            dao.clearEntries()
            dao.clearMenus()
            dao.clearDishes()
            dao.clearFiles()
            dao.clearMonths()
            dao.clearTypes()
            dao.upsertTypes(data.menuTypes.map { it.toEntity() })
            dao.insertMonths(data.months.map { it.toEntity() })
            dao.upsertFiles(data.menuFiles.map { it.toEntity() })
            dao.insertDishes(data.dishes.map { it.toEntity() })
            dao.upsertMenus(data.dailyMenus.map { it.toEntity() })
            dao.insertEntries(data.dailyEntries.map { DailyEntryEntity(it.date.toString()) })
            dao.upsertLines(data.dailyEntries.flatMap { entry -> entry.lines.map { it.toEntity(entry.date) } })
        }
    }
}
