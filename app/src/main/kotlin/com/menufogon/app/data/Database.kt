package com.menufogon.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.menufogon.core.model.MenuType
import kotlinx.coroutines.flow.Flow

// Dates are stored as ISO text (yyyy-MM-dd), which also sorts and compares correctly.

@Entity(tableName = "months", primaryKeys = ["year", "month"])
data class MonthEntity(val year: Int, val month: Int)

@Entity(tableName = "menu_types")
data class MenuTypeEntity(@PrimaryKey val id: Int, val name: String)

@Entity(tableName = "menu_files", primaryKeys = ["year", "month", "typeId"])
data class MenuFileEntity(
    val year: Int,
    val month: Int,
    val typeId: Int,
    val fileName: String,
    val importedAtEpochMs: Long,
)

@Entity(tableName = "dishes", indices = [Index(value = ["normalizedName"], unique = true)])
data class DishEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val normalizedName: String,
    val ingredients: String,
    val allergens: String,
)

@Entity(
    tableName = "daily_menus",
    primaryKeys = ["date", "typeId"],
    indices = [Index("firstDishId"), Index("secondDishId")],
)
data class DailyMenuEntity(
    val date: String,
    val typeId: Int,
    val firstDishId: Long?,
    val secondDishId: Long?,
    val firstAllergens: String,
    val secondAllergens: String,
)

@Entity(tableName = "daily_entries")
data class DailyEntryEntity(@PrimaryKey val date: String)

@Entity(
    tableName = "entry_lines",
    primaryKeys = ["date", "typeId"],
    foreignKeys = [
        ForeignKey(
            entity = DailyEntryEntity::class,
            parentColumns = ["date"],
            childColumns = ["date"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EntryLineEntity(
    val date: String,
    val typeId: Int,
    val typeName: String,
    val count: Int,
    val firstName: String?,
    val firstAllergens: String,
    val secondName: String?,
    val secondAllergens: String,
)

/** A daily menu joined with its dish names. */
data class MenuWithDishes(
    val date: String,
    val typeId: Int,
    val firstDishId: Long?,
    val secondDishId: Long?,
    val firstAllergens: String,
    val secondAllergens: String,
    @ColumnInfo(name = "firstName") val firstName: String?,
    @ColumnInfo(name = "secondName") val secondName: String?,
)

@Dao
interface MenuDao {
    // ---- types
    @Query("SELECT * FROM menu_types ORDER BY id")
    fun observeTypes(): Flow<List<MenuTypeEntity>>

    @Query("SELECT * FROM menu_types ORDER BY id")
    suspend fun getTypes(): List<MenuTypeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTypes(types: List<MenuTypeEntity>)

    // ---- months and files
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMonths(months: List<MonthEntity>)

    @Query("SELECT * FROM months ORDER BY year, month")
    suspend fun getMonths(): List<MonthEntity>

    @Query("SELECT * FROM menu_files WHERE year = :year AND month = :month")
    fun observeFiles(year: Int, month: Int): Flow<List<MenuFileEntity>>

    @Query("SELECT * FROM menu_files ORDER BY year, month, typeId")
    suspend fun getFiles(): List<MenuFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFiles(files: List<MenuFileEntity>)

    // ---- dishes
    @Query("SELECT * FROM dishes ORDER BY normalizedName")
    fun observeDishes(): Flow<List<DishEntity>>

    @Query("SELECT * FROM dishes ORDER BY id")
    suspend fun getDishes(): List<DishEntity>

    @Query("SELECT * FROM dishes WHERE normalizedName = :normalizedName")
    suspend fun findDish(normalizedName: String): DishEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDish(dish: DishEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDishes(dishes: List<DishEntity>)

    @Update
    suspend fun updateDish(dish: DishEntity)

    @Query("DELETE FROM dishes WHERE id = :id")
    suspend fun deleteDish(id: Long)

    @Query("UPDATE daily_menus SET firstDishId = :newId WHERE firstDishId = :oldId")
    suspend fun repointFirst(oldId: Long, newId: Long)

    @Query("UPDATE daily_menus SET secondDishId = :newId WHERE secondDishId = :oldId")
    suspend fun repointSecond(oldId: Long, newId: Long)

    // ---- daily menus
    @Query(
        """
        SELECT m.date, m.typeId, m.firstDishId, m.secondDishId, m.firstAllergens, m.secondAllergens,
               f.displayName AS firstName, s.displayName AS secondName
        FROM daily_menus m
        LEFT JOIN dishes f ON f.id = m.firstDishId
        LEFT JOIN dishes s ON s.id = m.secondDishId
        WHERE m.date BETWEEN :from AND :to
        ORDER BY m.date, m.typeId
        """,
    )
    fun observeMenus(from: String, to: String): Flow<List<MenuWithDishes>>

    @Query(
        """
        SELECT m.date, m.typeId, m.firstDishId, m.secondDishId, m.firstAllergens, m.secondAllergens,
               f.displayName AS firstName, s.displayName AS secondName
        FROM daily_menus m
        LEFT JOIN dishes f ON f.id = m.firstDishId
        LEFT JOIN dishes s ON s.id = m.secondDishId
        WHERE m.date BETWEEN :from AND :to
        ORDER BY m.date, m.typeId
        """,
    )
    suspend fun getMenus(from: String, to: String): List<MenuWithDishes>

    @Query("SELECT * FROM daily_menus ORDER BY date, typeId")
    suspend fun getAllMenus(): List<DailyMenuEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMenus(menus: List<DailyMenuEntity>)

    @Query("DELETE FROM daily_menus WHERE typeId = :typeId AND date BETWEEN :from AND :to")
    suspend fun deleteMenus(typeId: Int, from: String, to: String)

    // ---- daily entries
    @Query("SELECT date FROM daily_entries WHERE date BETWEEN :from AND :to")
    fun observeEntryDates(from: String, to: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM daily_entries")
    fun observeEntryCount(): Flow<Int>

    @Query("SELECT * FROM entry_lines WHERE date = :date ORDER BY typeId")
    suspend fun getLines(date: String): List<EntryLineEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM daily_entries WHERE date = :date)")
    suspend fun hasEntry(date: String): Boolean

    @Query("SELECT date FROM daily_entries ORDER BY date")
    suspend fun getEntryDates(): List<String>

    @Query("SELECT * FROM entry_lines ORDER BY date, typeId")
    suspend fun getAllLines(): List<EntryLineEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(entries: List<DailyEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<EntryLineEntity>)

    @Query("DELETE FROM entry_lines WHERE date = :date")
    suspend fun deleteLines(date: String)

    @Query("DELETE FROM daily_entries WHERE date = :date")
    suspend fun deleteEntry(date: String)

    // ---- wipe (backup import)
    @Query("DELETE FROM entry_lines") suspend fun clearLines()
    @Query("DELETE FROM daily_entries") suspend fun clearEntries()
    @Query("DELETE FROM daily_menus") suspend fun clearMenus()
    @Query("DELETE FROM dishes") suspend fun clearDishes()
    @Query("DELETE FROM menu_files") suspend fun clearFiles()
    @Query("DELETE FROM months") suspend fun clearMonths()
    @Query("DELETE FROM menu_types") suspend fun clearTypes()
}

@Database(
    entities = [
        MonthEntity::class,
        MenuTypeEntity::class,
        MenuFileEntity::class,
        DishEntity::class,
        DailyMenuEntity::class,
        DailyEntryEntity::class,
        EntryLineEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun menuDao(): MenuDao

    companion object {
        const val VERSION = 1
        const val NAME = "menufogon.db"

        /**
         * Schema migrations, in order. Version 1 is the first schema, so the list is empty.
         * When the schema changes: raise [VERSION], add a Migration here and commit the new
         * JSON in app/schemas. Destructive migration is never enabled: an update must keep the data.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        /** Inserts the three fixed menu types the first time the database is created. */
        val SEED = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                for (type in MenuType.DEFAULTS) {
                    db.execSQL("INSERT OR IGNORE INTO menu_types (id, name) VALUES (?, ?)", arrayOf<Any>(type.id, type.name))
                }
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .addCallback(SEED)
                .build()
    }
}
