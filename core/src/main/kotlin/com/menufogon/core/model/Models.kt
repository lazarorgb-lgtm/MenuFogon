package com.menufogon.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.ZoneId

/** All dates in the app are civil dates in this zone. */
val APP_ZONE: ZoneId = ZoneId.of("Europe/Madrid")

object LocalDateIsoSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

typealias IsoDate = @Serializable(with = LocalDateIsoSerializer::class) LocalDate

@Serializable
data class MonthRef(val year: Int, val month: Int)

/** Menu type. The id is fixed; the name can be edited by the user. */
@Serializable
data class MenuType(val id: Int, val name: String) {
    companion object {
        const val NORMAL = 1
        const val DIABETIC = 2
        const val EEI = 3
        val DEFAULTS = listOf(
            MenuType(NORMAL, "Normal"),
            MenuType(DIABETIC, "Diabético"),
            MenuType(EEI, "EEI"),
        )
        val IDS = setOf(NORMAL, DIABETIC, EEI)
    }
}

enum class Course { FIRST, SECOND }

/** Metadata of an imported PDF. One per month and type. */
@Serializable
data class MenuFile(
    val year: Int,
    val month: Int,
    val typeId: Int,
    val fileName: String,
    val importedAtEpochMs: Long,
)

/** A dish, reused between days and types. */
@Serializable
data class Dish(
    val id: Long,
    val displayName: String,
    val normalizedName: String,
    val ingredients: String = "",
    val allergens: String = "",
)

/** Published menu for one date and one type. Null dish id = no dish for that course. */
@Serializable
data class DailyMenu(
    val date: IsoDate,
    val typeId: Int,
    val firstDishId: Long? = null,
    val secondDishId: Long? = null,
    val firstAllergens: String = "",
    val secondAllergens: String = "",
)

/** Copy of what one menu type offered on the day an entry was saved. */
@Serializable
data class EntryLine(
    val typeId: Int,
    val typeName: String,
    val count: Int,
    val firstName: String? = null,
    val firstAllergens: String = "",
    val secondName: String? = null,
    val secondAllergens: String = "",
)

/**
 * Daily record of served menus. Dish names are copied into [lines] so the history
 * does not change if a PDF is imported again later.
 */
@Serializable
data class DailyEntry(
    val date: IsoDate,
    val lines: List<EntryLine>,
) {
    val totalDiners: Int get() = lines.sumOf { it.count }
}
