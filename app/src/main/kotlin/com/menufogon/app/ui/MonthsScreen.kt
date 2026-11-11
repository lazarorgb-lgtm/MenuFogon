package com.menufogon.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.menufogon.app.data.MenuRepository
import com.menufogon.app.data.ReviewedDay
import com.menufogon.app.pdf.PdfTextExtractor
import com.menufogon.core.model.MenuFile
import com.menufogon.core.model.MenuType
import com.menufogon.core.parser.DayIssue
import com.menufogon.core.parser.MenuParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class ReviewRow(
    val date: LocalDate,
    /** False for days the PDF does not contain; they are not saved unless the user fills them in. */
    val included: Boolean,
    val first: String = "",
    val firstIngredients: String = "",
    val firstAllergens: String = "",
    val noFirst: Boolean = false,
    val second: String = "",
    val secondIngredients: String = "",
    val secondAllergens: String = "",
    val doubtful: Boolean = false,
) {
    val missingFirst: Boolean get() = included && !noFirst && first.isBlank()
    val missingSecond: Boolean get() = included && second.isBlank()
    val needsAttention: Boolean get() = missingFirst || missingSecond || doubtful
}

data class ReviewState(
    val month: YearMonth,
    val typeId: Int,
    val fileName: String,
    val rows: List<ReviewRow>,
    /** Message shown above the table, e.g. when the PDF could not be read. */
    val notice: String? = null,
)

data class SharedPdf(val uri: Uri, val fileName: String, val suggestedTypeId: Int?)

data class MonthsState(
    val month: YearMonth,
    val types: List<MenuType> = emptyList(),
    val files: Map<Int, MenuFile> = emptyMap(),
    val dayCounts: Map<Int, Int> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class MonthsViewModel(
    private val repository: MenuRepository,
    private val extractor: PdfTextExtractor,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.from(today()))

    val state: StateFlow<MonthsState> = combine(
        month,
        repository.observeTypes(),
        month.flatMapLatest { repository.observeFiles(it) },
        month.flatMapLatest { repository.observeMenus(it) },
    ) { m, types, files, menus ->
        MonthsState(m, types, files.associateBy { it.typeId }, menus.groupingBy { it.typeId }.eachCount())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonthsState(month.value))

    private val _review = MutableStateFlow<ReviewState?>(null)
    val review: StateFlow<ReviewState?> = _review

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _shared = MutableStateFlow<SharedPdf?>(null)
    val shared: StateFlow<SharedPdf?> = _shared

    fun moveMonth(delta: Long) = month.update { it.plusMonths(delta) }
    fun showMonth(target: YearMonth) { month.value = target }

    /** A PDF arrived through "Compartir" from another app. */
    fun receiveShared(uri: Uri) {
        val name = extractor.displayName(uri)
        _shared.value = SharedPdf(uri, name, MenuParser.suggestTypeId(name))
    }

    fun dismissShared() { _shared.value = null }

    fun importPdf(uri: Uri, typeId: Int) {
        val target = month.value
        _shared.value = null
        _busy.value = true
        viewModelScope.launch {
            val fileName = extractor.displayName(uri)
            val result = runCatching { extractor.extract(uri) }
            val parsed = result.getOrNull()?.let { MenuParser().parse(it.items, target) }
            _review.value = when {
                parsed == null || parsed.isEmpty -> ReviewState(
                    target, typeId, fileName, blankRows(target),
                    "No se ha podido leer el menú de este PDF. Puedes introducir la tabla a mano y guardarla.",
                )
                else -> {
                    val byDate = parsed.days.associateBy { it.date }
                    val rows = blankRows(target).map { blank ->
                        val day = byDate[blank.date] ?: return@map blank
                        ReviewRow(
                            date = day.date,
                            included = true,
                            first = day.first?.name.orEmpty(),
                            firstIngredients = day.first?.ingredients.orEmpty(),
                            firstAllergens = day.first?.allergens.orEmpty(),
                            noFirst = day.noFirst,
                            second = day.second?.name.orEmpty(),
                            secondIngredients = day.second?.ingredients.orEmpty(),
                            secondAllergens = day.second?.allergens.orEmpty(),
                            doubtful = DayIssue.DOUBTFUL_TEXT in day.issues ||
                                DayIssue.ORPHAN_ALLERGENS in day.issues ||
                                DayIssue.WEEKDAY_MISMATCH in day.issues,
                        )
                    }
                    val notice = if ((result.getOrNull()?.ocrPages ?: 0) > 0) {
                        "Este PDF es una imagen y se ha leído con reconocimiento de texto. Revisa bien cada día."
                    } else {
                        null
                    }
                    ReviewState(target, typeId, fileName, rows, notice)
                }
            }
            _busy.value = false
        }
    }

    /** Opens the table to type or correct the menu by hand, starting from what is already saved. */
    fun editByHand(typeId: Int) {
        val target = month.value
        viewModelScope.launch {
            val saved = repository.getMenus(target).filter { it.typeId == typeId }.associateBy { it.date }
            val rows = blankRows(target).map { blank ->
                val menu = saved[blank.date] ?: return@map blank
                blank.copy(
                    included = true,
                    first = menu.firstName.orEmpty(),
                    firstAllergens = menu.firstAllergens,
                    noFirst = menu.firstName == null,
                    second = menu.secondName.orEmpty(),
                    secondAllergens = menu.secondAllergens,
                )
            }
            val fileName = state.value.files[typeId]?.fileName ?: "Introducido a mano"
            _review.value = ReviewState(target, typeId, fileName, rows)
        }
    }

    private fun blankRows(target: YearMonth): List<ReviewRow> =
        (1..target.lengthOfMonth()).map { ReviewRow(target.atDay(it), included = false) }

    fun updateRow(index: Int, change: (ReviewRow) -> ReviewRow) {
        _review.update { current ->
            current?.copy(rows = current.rows.mapIndexed { i, row -> if (i == index) change(row) else row })
        }
    }

    fun cancelReview() { _review.value = null }

    /** Nothing is stored until this is called. */
    fun confirmReview() {
        val current = _review.value ?: return
        _busy.value = true
        viewModelScope.launch {
            val days = current.rows.filter { it.included }.map { row ->
                ReviewedDay(
                    date = row.date,
                    firstName = if (row.noFirst) "" else row.first,
                    firstIngredients = row.firstIngredients,
                    firstAllergens = row.firstAllergens,
                    secondName = row.second,
                    secondIngredients = row.secondIngredients,
                    secondAllergens = row.secondAllergens,
                )
            }
            repository.saveMonthMenu(current.month, current.typeId, current.fileName, days, System.currentTimeMillis())
            _review.value = null
            _busy.value = false
        }
    }
}

@Composable
fun MonthsScreen(viewModel: MonthsViewModel, contentPadding: PaddingValues) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val shared by viewModel.shared.collectAsStateWithLifecycle()

    var pickerTypeId by remember { mutableIntStateOf(MenuType.NORMAL) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPdf(uri, pickerTypeId)
    }

    val currentReview = review
    when {
        busy -> Column(
            Modifier.fillMaxSize().padding(contentPadding),
            Arrangement.Center,
            Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Leyendo el menú…")
        }

        currentReview != null -> ReviewTable(currentReview, state.types, viewModel, contentPadding)

        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Switcher(
                    label = state.month.label(),
                    previousDescription = "Mes anterior",
                    nextDescription = "Mes siguiente",
                    onPrevious = { viewModel.moveMonth(-1) },
                    onNext = { viewModel.moveMonth(1) },
                )
            }
            item {
                Text(
                    "Carga el PDF de cada tipo de menú. Puedes cargar solo uno: el mes funciona con los tipos que tenga.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            itemsIndexed(state.types, key = { _, type -> type.id }) { _, type ->
                val file = state.files[type.id]
                val days = state.dayCounts[type.id] ?: 0
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(type.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(if (days > 0) "Cargado · $days días" else "Sin PDF")
                        if (file != null && days > 0) {
                            Text(file.fileName, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                pickerTypeId = type.id
                                picker.launch(arrayOf("application/pdf"))
                            }) { Text(if (days > 0) "Reemplazar PDF" else "Cargar PDF") }
                            OutlinedButton(onClick = { viewModel.editByHand(type.id) }) {
                                Text(if (days > 0) "Corregir" else "A mano")
                            }
                        }
                    }
                }
            }
        }
    }

    val pending = shared
    if (pending != null && !busy && currentReview == null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissShared,
            title = { Text("PDF recibido") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.fileName, style = MaterialTheme.typography.bodySmall)
                    Text("Elige el mes y el tipo de menú de este archivo.")
                    Switcher(
                        label = state.month.label(),
                        previousDescription = "Mes anterior",
                        nextDescription = "Mes siguiente",
                        onPrevious = { viewModel.moveMonth(-1) },
                        onNext = { viewModel.moveMonth(1) },
                    )
                    for (type in state.types) {
                        val suggested = type.id == pending.suggestedTypeId
                        val label = if (suggested) "${type.name} (sugerido)" else type.name
                        if (suggested) {
                            Button(onClick = { viewModel.importPdf(pending.uri, type.id) }, Modifier.fillMaxWidth()) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { viewModel.importPdf(pending.uri, type.id) }, Modifier.fillMaxWidth()) { Text(label) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismissShared) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ReviewTable(
    review: ReviewState,
    types: List<MenuType>,
    viewModel: MonthsViewModel,
    contentPadding: PaddingValues,
) {
    BackHandler(onBack = viewModel::cancelReview)
    val typeName = types.firstOrNull { it.id == review.typeId }?.name.orEmpty()
    val included = review.rows.count { it.included }
    val pending = review.rows.count { it.needsAttention }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Revisión · $typeName · ${review.month.label()}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(review.fileName, style = MaterialTheme.typography.bodySmall)
                if (review.notice != null) {
                    Text(review.notice, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    if (pending > 0) "$included días con menú · $pending por revisar (marcados en color)"
                    else "$included días con menú. No se guarda nada hasta que pulses Guardar.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::confirmReview, enabled = included > 0) { Text("Guardar menú") }
                    OutlinedButton(onClick = viewModel::cancelReview) { Text("Cancelar") }
                }
            }
        }
        itemsIndexed(review.rows, key = { _, row -> row.date.toEpochDay() }) { index, row ->
            ReviewRowCard(row) { change -> viewModel.updateRow(index, change) }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::confirmReview, enabled = included > 0) { Text("Guardar menú") }
                OutlinedButton(onClick = viewModel::cancelReview) { Text("Cancelar") }
            }
        }
    }
}

@Composable
private fun ReviewRowCard(row: ReviewRow, onChange: ((ReviewRow) -> ReviewRow) -> Unit) {
    val colors = when {
        !row.included -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        row.missingFirst || row.missingSecond -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        row.doubtful -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        else -> CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = colors) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.date.longLabel(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            if (!row.included) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sin menú este día", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onChange { it.copy(included = true) } }) { Text("Añadir") }
                }
                return@Column
            }
            if (row.missingFirst || row.missingSecond) {
                Text("Falta un plato por reconocer. Escríbelo o marca «Sin primero».", style = MaterialTheme.typography.bodySmall)
            } else if (row.doubtful) {
                Text("Texto dudoso: comprueba que está bien leído.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = if (row.noFirst) "" else row.first,
                onValueChange = { text -> onChange { it.copy(first = text, doubtful = false) } },
                label = { Text("Primero") },
                enabled = !row.noFirst,
                isError = row.missingFirst,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.noFirst, onCheckedChange = { checked -> onChange { it.copy(noFirst = checked) } })
                Text("Sin primero")
            }
            if (!row.noFirst) {
                AllergenField("Alérgenos del primero", row.firstAllergens, row.first.isNotBlank()) { text ->
                    onChange { it.copy(firstAllergens = text) }
                }
            }
            OutlinedTextField(
                value = row.second,
                onValueChange = { text -> onChange { it.copy(second = text, doubtful = false) } },
                label = { Text("Segundo") },
                isError = row.missingSecond,
                modifier = Modifier.fillMaxWidth(),
            )
            AllergenField("Alérgenos del segundo", row.secondAllergens, row.second.isNotBlank()) { text ->
                onChange { it.copy(secondAllergens = text) }
            }
            TextButton(onClick = { onChange { ReviewRow(it.date, included = false) } }) { Text("Quitar este día") }
        }
    }
}

@Composable
private fun AllergenField(label: String, value: String, hasDish: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = if (hasDish && value.isBlank()) {
            { Text("Sin alérgenos asignados") }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
