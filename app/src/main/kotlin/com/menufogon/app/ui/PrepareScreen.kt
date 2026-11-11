package com.menufogon.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.menufogon.app.data.MenuRepository
import com.menufogon.core.prep.DishPrep
import com.menufogon.core.prep.PreparationCalculator
import com.menufogon.core.prep.PreparationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PrepareViewModel(private val repository: MenuRepository) : ViewModel() {
    private val _date = MutableStateFlow(today())
    val date: StateFlow<LocalDate> = _date

    private val _result = MutableStateFlow<PreparationResult?>(null)
    val result: StateFlow<PreparationResult?> = _result

    /** Null while loading. The screen is only useful once some entry exists. */
    val entryCount: StateFlow<Int?> = repository.observeEntryCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { refresh() }

    fun setDate(date: LocalDate) {
        _date.value = date
        refresh()
    }

    fun refresh() {
        val target = _date.value
        viewModelScope.launch {
            val calculated = PreparationCalculator.calculate(target, repository.getEntry(target))
            if (_date.value == target) _result.value = calculated
        }
    }
}

@Composable
fun PrepareScreen(viewModel: PrepareViewModel, contentPadding: PaddingValues) {
    val date by viewModel.date.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val entryCount by viewModel.entryCount.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Switcher(
            label = date.longLabel(),
            previousDescription = "Día anterior",
            nextDescription = "Día siguiente",
            onPrevious = { viewModel.setDate(date.minusDays(1)) },
            onNext = { viewModel.setDate(date.plusDays(1)) },
        )
        OutlinedButton(onClick = { showPicker = true }) { Text("Elegir otra fecha") }

        when (val current = result) {
            null -> Text("Cargando…")
            is PreparationResult.NoEntry -> Card(Modifier.fillMaxWidth()) {
                Text(
                    if (entryCount == 0) {
                        "Todavía no hay ningún registro. Abre un día en el Calendario y guarda los comensales para ver aquí qué preparar."
                    } else {
                        "No hay registro para esta fecha. Abre ese día en el Calendario y guarda los comensales."
                    },
                    Modifier.padding(16.dp),
                )
            }
            is PreparationResult.Ready -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Primeros: ${current.totalFirsts}", style = MaterialTheme.typography.headlineSmall)
                        Text("Segundos: ${current.totalSeconds}", style = MaterialTheme.typography.headlineSmall)
                        Text("Comensales: ${current.totalDiners}")
                    }
                }
                DishSection("Primeros platos", current.firsts)
                DishSection("Segundos platos", current.seconds)
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showPicker = false
                    // The picker works with UTC midnights: read the date back in UTC.
                    pickerState.selectedDateMillis?.let {
                        viewModel.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancelar") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun DishSection(title: String, dishes: List<DishPrep>) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (dishes.isEmpty()) {
        Text("Nada que preparar.")
        return
    }
    for (dish in dishes) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(dish.describe(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (dish.allergens.isBlank()) "Sin alérgenos declarados" else "Alérgenos: ${dish.allergens}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
