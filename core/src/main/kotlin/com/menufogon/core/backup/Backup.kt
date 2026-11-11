package com.menufogon.core.backup

import com.menufogon.core.model.DailyEntry
import com.menufogon.core.model.DailyMenu
import com.menufogon.core.model.Dish
import com.menufogon.core.model.MenuFile
import com.menufogon.core.model.MenuType
import com.menufogon.core.model.MonthRef
import com.menufogon.core.validation.QuantityValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Everything the app stores. The original PDF files are not part of it. */
@Serializable
data class BackupData(
    val months: List<MonthRef> = emptyList(),
    val menuTypes: List<MenuType> = emptyList(),
    val menuFiles: List<MenuFile> = emptyList(),
    val dishes: List<Dish> = emptyList(),
    val dailyMenus: List<DailyMenu> = emptyList(),
    val dailyEntries: List<DailyEntry> = emptyList(),
)

@Serializable
data class BackupFile(
    val schemaVersion: Int,
    val app: String = BackupCodec.APP_ID,
    /** ISO-8601 instant of the export. */
    val exportedAt: String,
    val data: BackupData,
)

data class BackupSummary(
    val exportedAt: Instant,
    val months: Int,
    val daysWithMenu: Int,
    val entries: Int,
)

sealed interface BackupReadResult {
    data class Ok(val file: BackupFile, val summary: BackupSummary) : BackupReadResult
    data class UnsupportedVersion(val version: Int) : BackupReadResult
    data class Invalid(val reason: String) : BackupReadResult
}

object BackupCodec {
    const val SCHEMA_VERSION = 1
    const val APP_ID = "MenuFogon"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val lenient = Json { ignoreUnknownKeys = true }

    fun fileName(date: LocalDate): String = "MenuCal-copia-" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + ".json"

    fun encode(data: BackupData, exportedAt: Instant): String =
        json.encodeToString(BackupFile.serializer(), BackupFile(SCHEMA_VERSION, APP_ID, exportedAt.toString(), data))

    /** Reads and fully validates a backup. Never throws. */
    fun decode(text: String): BackupReadResult {
        val version = try {
            val root = lenient.parseToJsonElement(text).jsonObject
            root["schemaVersion"]?.jsonPrimitive?.int ?: return BackupReadResult.Invalid("schemaVersion missing")
        } catch (e: Exception) {
            return BackupReadResult.Invalid("not a JSON backup")
        }
        if (version != SCHEMA_VERSION) return BackupReadResult.UnsupportedVersion(version)

        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            return BackupReadResult.Invalid("bad structure: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: Exception) {
            // LocalDate.parse and similar failures inside serializers.
            return BackupReadResult.Invalid("bad value: ${e.message}")
        }
        val exportedAt = try {
            Instant.parse(file.exportedAt)
        } catch (e: Exception) {
            return BackupReadResult.Invalid("bad export date")
        }
        validate(file.data)?.let { return BackupReadResult.Invalid(it) }
        return BackupReadResult.Ok(file, summary(file.data, exportedAt))
    }

    fun summary(data: BackupData, exportedAt: Instant) = BackupSummary(
        exportedAt = exportedAt,
        months = data.months.size,
        daysWithMenu = data.dailyMenus.map { it.date }.distinct().size,
        entries = data.dailyEntries.size,
    )

    /** Returns a description of the first structural problem, or null when the data is consistent. */
    fun validate(data: BackupData): String? {
        val typeIds = data.menuTypes.map { it.id }
        if (typeIds.toSet() != MenuType.IDS || typeIds.size != MenuType.IDS.size) return "menu types must be exactly ${MenuType.IDS}"
        if (data.menuTypes.any { it.name.isBlank() }) return "menu type without name"
        if (data.months.any { it.month !in 1..12 || it.year !in 2000..2999 }) return "month out of range"
        if (data.months.distinct().size != data.months.size) return "duplicated month"
        val months = data.months.toSet()
        for (f in data.menuFiles) {
            if (f.typeId !in MenuType.IDS) return "menu file with unknown type"
            if (MonthRef(f.year, f.month) !in months) return "menu file for a month that is not listed"
        }
        if (data.menuFiles.map { Triple(it.year, it.month, it.typeId) }.distinct().size != data.menuFiles.size) {
            return "duplicated menu file"
        }
        val dishIds = data.dishes.map { it.id }.toSet()
        if (dishIds.size != data.dishes.size) return "duplicated dish id"
        if (data.dishes.any { it.displayName.isBlank() || it.normalizedName.isBlank() }) return "dish without name"
        if (data.dishes.map { it.normalizedName }.distinct().size != data.dishes.size) return "duplicated dish name"
        for (m in data.dailyMenus) {
            if (m.typeId !in MenuType.IDS) return "daily menu with unknown type"
            if (m.firstDishId != null && m.firstDishId !in dishIds) return "daily menu with unknown first dish"
            if (m.secondDishId != null && m.secondDishId !in dishIds) return "daily menu with unknown second dish"
        }
        if (data.dailyMenus.map { it.date to it.typeId }.distinct().size != data.dailyMenus.size) return "duplicated daily menu"
        if (data.dailyEntries.map { it.date }.distinct().size != data.dailyEntries.size) return "duplicated daily entry"
        for (e in data.dailyEntries) {
            if (e.lines.any { it.typeId !in MenuType.IDS }) return "entry with unknown type"
            if (e.lines.map { it.typeId }.distinct().size != e.lines.size) return "entry with repeated type"
            if (e.lines.any { !QuantityValidator.isValid(it.count) }) return "entry quantity out of range"
        }
        return null
    }
}

/** Something that can replace all its data at once, atomically. */
fun interface BackupTarget {
    /** Must apply everything or nothing. */
    fun replaceAll(data: BackupData)
}

object BackupImporter {
    /**
     * Validates [text] and only then replaces the data of [target].
     * An invalid or unsupported file leaves the target untouched.
     */
    fun import(text: String, target: BackupTarget): BackupReadResult {
        val result = BackupCodec.decode(text)
        if (result is BackupReadResult.Ok) target.replaceAll(result.file.data)
        return result
    }
}
