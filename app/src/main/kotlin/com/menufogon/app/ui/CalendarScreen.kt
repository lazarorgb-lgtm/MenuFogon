package com.menufogon.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.menufogon.app.BackupPrefs
import com.menufogon.app.data.DayMenu
import com.menufogon.app.data.MenuRepository
import com.menufogon.core.model.MenuType
import com.menufogon.core.validation.QuantityResult
import com.menufogon.core.validation.QuantityValidator
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
import java.util.concurrent.TimeUnit

data class CalendarState(
    val month: YearMonth,
    val types: List<MenuType> = emptyList(),
    /** Type ids with a published menu, by date. */
    val menus: Map<LocalDate, List<Int>> = emptyMap(),
    val entryDates: Set<LocalDate> = emptySet(),
    val backupWarning: String? = null,
) {
    val monthHasMenus: Boolean get() = menus.isNotEmpty()
}

data class DayRow(val type: MenuType, val menu: DayMenu?, val text: String) {
    val quantity: QuantityResult get() = QuantityValidator.parse(text)
}

data class DayState(val date: LocalDate, val rows: List<DayRow>, val exists: Boolean) {
    val valid: Boolean get() = rows.all { it.menu == null || it.quantity is QuantityResult.Valid }
    val total: Int get() = rows.sumOf { row -> (row.quantity as? QuantityResult.Valid)?.value?.takeIf { row.menu != null } ?: 0 }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val repository: MenuRepository,
    backupPrefs: BackupPrefs,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.from(today()))

    val state: StateFlow<CalendarState> = combine(
        month,
        repository.observeTypes(),
        month.flatMapLatest { repository.observeMenus(it) },
        month.flatMapLatest { repository.observeEntryDates(it) },
        backupPrefs.lastExport,
    ) { m, types, menus, entries, lastExport ->
        val warning = when {
            lastExport == null -> "Aún no has exportado ninguna copia de seguridad."
            System.currentTimeMillis() - lastExport > TimeUnit.DAYS.toMillis(7) ->
                "Han pasado más de 7 días desde la última copia de seguridad."
            else -> null
        }
        CalendarState(m, types, menus.groupBy({ it.date }, { it.typeId }), entries, warning)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarState(month.value))

    private val _day = MutableStateFlow<DayState?>(null)
    val day: StateFlow<DayState?> = _day

    fun moveMonth(delta: Long) = month.update { it.plusMonths(delta) }

    fun openDay(date: LocalDate) {
        viewModelScope.launch {
            val menus = repository.getMenus(date).associateBy { it.typeId }
            val entry = repository.getEntry(date)
            val counts = entry?.lines?.associate { it.typeId to it.count }.orEmpty()
            val rows = repository.getTypes().map { type ->
                DayRow(type, menus[type.id], (counts[type.id] ?: 0).toString())
            }
            _day.value = DayState(date, rows, entry != null)
        }
    }

    fun closeDay() { _day.value = null }

    fun setQuantity(typeId: Int, text: String) {
        // Only digits, at most four: the range is 0 to 9999.
        val clean = text.filter { it.isDigit() }.take(4)
        _day.update { current ->
            current?.copy(rows = current.rows.map { if (it.type.id == typeId) it.copy(text = clean) else it })
        }
    }

    fun step(typeId: Int, delta: Int) {
        val row = _day.value?.rows?.firstOrNull { it.type.id == typeId } ?: return
        val value = (row.quantity as? QuantityResult.Valid)?.value ?: 0
        setQuantity(typeId, (value + delta).coerceIn(QuantityValidator.MIN, QuantityValidator.MAX).toString())
    }

    fun saveDay() {
        val current = _day.value ?: return
        if (!current.valid) return
        viewModelScope.launch {
            val counts = current.rows.filter { it.menu != null }
                .associate { it.type.id to (it.quantity as QuantityResult.Valid).value }
            repository.saveEntry(current.date, counts)
            _day.value = null
        }
    }

    fun deleteDay() {
        val current = _day.value ?: return
        viewModelScope.launch {
            repository.deleteEntry(current.date)
            _day.value = null
        }
    }
}

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    contentPadding: PaddingValues,
    onGoToMonths: (YearMonth) -> Unit,
    onPrepare: (LocalDate) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val day by viewModel.day.collectAsStateWithLifecycle()
    var showNoMenus by remember { mutableStateOf(false) }

    val openDay = day
    if (openDay != null) {
        DayEditor(openDay, viewModel, contentPadding, onPrepare)
        return
    }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switcher(
            label = state.month.label(),
            previousDescription = "Mes anterior",
            nextDescription = "Mes siguiente",
            onPrevious = { viewModel.moveMonth(-1) },
            onNext = { viewModel.moveMonth(1) },
        )
        if (state.backupWarning != null) {
            Text(
                state.backupWarning!! + " Ve a Ajustes para exportar una.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.monthHasMenus) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Este mes no tiene ningún PDF cargado. Carga al menos uno para poder registrar.")
                    Button(onClick = { onGoToMonths(state.month) }) { Text("Ir a Meses") }
                }
            }
        }

        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            for (name in listOf("L", "M", "X", "J", "V", "S", "D")) {
                Text(name, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
        }
        val first = state.month.atDay(1)
        val offset = first.dayOfWeek.value - 1 // Monday first
        val cells = offset + state.month.lengthOfMonth()
        val initials = state.types.associate { it.id to it.name.take(1).uppercase(ES) }
        val names = state.types.associate { it.id to it.name }
        for (week in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (column in 0..6) {
                    val dayNumber = week * 7 + column - offset + 1
                    if (dayNumber < 1 || dayNumber > state.month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val date = state.month.atDay(dayNumber)
                    val typeIds = state.menus[date].orEmpty()
                    val hasEntry = date in state.entryDates
                    val description = buildString {
                        append(date.longLabel())
                        append(if (typeIds.isEmpty()) ". Sin menú publicado" else ". Menús: " + typeIds.joinToString(", ") { names[it].orEmpty() })
                        append(if (hasEntry) ". Con registro guardado" else ". Sin registro")
                    }
                    Surface(
                        onClick = { if (state.monthHasMenus) viewModel.openDay(date) else showNoMenus = true },
                        shape = MaterialTheme.shapes.small,
                        color = if (hasEntry) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            if (date == today()) 2.dp else 1.dp,
                            if (date == today()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.weight(1f).aspectRatio(0.8f).clearAndSetSemantics { contentDescription = description },
                    ) {
                        Column(Modifier.padding(2.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                            Text(dayNumber.toString(), fontWeight = if (hasEntry) FontWeight.Bold else FontWeight.Normal)
                            Text(
                                typeIds.joinToString(" ") { initials[it].orEmpty() },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                            Text(if (hasEntry) "✓" else " ", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Text(
            "Las letras indican qué tipos tienen menú (" + state.types.joinToString(", ") { "${initials[it.id]} = ${it.name}" } +
                "). Los días con ✓ tienen registro guardado.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (showNoMenus) {
        AlertDialog(
            onDismissRequest = { showNoMenus = false },
            title = { Text("Mes sin menús") },
            text = { Text("Este mes no tiene ningún PDF cargado. Para registrar comensales, carga primero el menú en Meses.") },
            confirmButton = {
                TextButton(onClick = {
                    showNoMenus = false
                    onGoToMonths(state.month)
                }) { Text("Ir a Meses") }
            },
            dismissButton = { TextButton(onClick = { showNoMenus = false }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun DayEditor(
    day: DayState,
    viewModel: CalendarViewModel,
    contentPadding: PaddingValues,
    onPrepare: (LocalDate) -> Unit,
) {
    BackHandler(onBack = viewModel::closeDay)
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(day.date.longLabel(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(if (day.exists) "Registro guardado. Puedes cambiarlo." else "Sin registro todavía.", style = MaterialTheme.typography.bodySmall)

        for (row in day.rows) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.type.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    val menu = row.menu
                    if (menu == null) {
                        Text("Sin menú publicado")
                    } else {
                        Text("Primero: " + (menu.firstName ?: "no hay"))
                        Text("Segundo: " + (menu.secondName ?: "no hay"))
                        val invalid = row.quantity !is QuantityResult.Valid
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalIconButton(
                                onClick = { viewModel.step(row.type.id, -1) },
                                modifier = Modifier.semantics { contentDescription = "Restar uno a ${row.type.name}" },
                            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                            OutlinedTextField(
                                value = row.text,
                                onValueChange = { viewModel.setQuantity(row.type.id, it) },
                                label = { Text("Comensales ${row.type.name}") },
                                singleLine = true,
                                isError = invalid,
                                supportingText = if (invalid) {
                                    { Text("Escribe un número entre 0 y 9999") }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(160.dp),
                            )
                            FilledTonalIconButton(
                                onClick = { viewModel.step(row.type.id, 1) },
                                modifier = Modifier.semantics { contentDescription = "Sumar uno a ${row.type.name}" },
                            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
            }
        }

        Text("Total de comensales: ${day.total}", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::saveDay, enabled = day.valid && day.rows.any { it.menu != null }) { Text("Guardar") }
            OutlinedButton(onClick = viewModel::closeDay) { Text("Cancelar") }
        }
        if (day.exists) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.closeDay()
                    onPrepare(day.date)
                }) { Text("Ver qué preparar") }
                TextButton(onClick = { confirmDelete = true }) { Text("Borrar registro") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Borrar registro") },
            text = { Text("Se borrará el registro del ${day.date.longLabel()}. No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteDay()
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}
