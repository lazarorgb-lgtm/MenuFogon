package com.menufogon.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.menufogon.app.data.MenuRepository
import com.menufogon.core.stats.DishCount
import com.menufogon.core.stats.Period
import com.menufogon.core.stats.Statistics
import com.menufogon.core.stats.StatisticsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

fun Period.label(): String = when (this) {
    is Period.Week -> "Semana $week de $weekBasedYear (${start.shortLabel()} – ${end.shortLabel()})"
    is Period.Month -> java.time.YearMonth.of(year, month).label()
    is Period.YearPeriod -> "Año $year"
}

data class StatsState(
    val period: Period,
    /** Null until the user presses "Generar" for the current period. */
    val statistics: Statistics? = null,
    val loading: Boolean = false,
)

class StatsViewModel(private val repository: MenuRepository) : ViewModel() {
    private val _state = MutableStateFlow(StatsState(Period.Week.of(today())))
    val state: StateFlow<StatsState> = _state

    // Changing the period clears the results: they are only calculated on request.
    fun setPeriod(period: Period) { _state.value = StatsState(period) }

    fun selectWeek() = setPeriod(Period.Week.of(anchor()))
    fun selectMonth() = setPeriod(Period.Month.of(anchor()))
    fun selectYear() = setPeriod(Period.YearPeriod.of(anchor()))

    /** Keeps the user near the period they were looking at when switching kind. */
    private fun anchor() = _state.value.period.let { if (today() in it) today() else it.start }

    fun generate() {
        val period = _state.value.period
        _state.value = StatsState(period, loading = true)
        viewModelScope.launch {
            val names = repository.getTypes().associate { it.id to it.name }
            val statistics = StatisticsCalculator.calculate(period, repository.getAllEntries(), names)
            if (_state.value.period == period) _state.value = StatsState(period, statistics)
        }
    }
}

@Composable
fun StatsScreen(viewModel: StatsViewModel, contentPadding: PaddingValues) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val period = state.period

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(period is Period.Week, onClick = viewModel::selectWeek, label = { Text("Semana") })
            FilterChip(period is Period.Month, onClick = viewModel::selectMonth, label = { Text("Mes") })
            FilterChip(period is Period.YearPeriod, onClick = viewModel::selectYear, label = { Text("Año") })
        }
        Switcher(
            label = period.label(),
            previousDescription = "Periodo anterior",
            nextDescription = "Periodo siguiente",
            onPrevious = { viewModel.setPeriod(period.previous()) },
            onNext = { viewModel.setPeriod(period.next()) },
        )
        Button(onClick = viewModel::generate, enabled = !state.loading) { Text("Generar") }

        val stats = state.statistics
        when {
            state.loading -> Text("Calculando…")
            stats == null -> Text("Elige el periodo y pulsa Generar.")
            !stats.hasData -> Text("No hay registros con comensales en este periodo.")
            else -> StatsContent(stats)
        }
    }
}

@Composable
private fun StatsContent(stats: Statistics) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Días con servicio: ${stats.daysWithService}")
            Text("Comensales totales: ${stats.totalDiners}")
            Text("Media diaria: " + String.format(ES, "%.1f", stats.dailyAverage))
            stats.busiestDay?.let { Text("Día con más comensales: ${it.date.shortLabel()} (${it.diners})") }
            stats.quietestDay?.let { Text("Día con menos comensales: ${it.date.shortLabel()} (${it.diners})") }
            val variation = stats.variationPercent
            Text(
                if (variation == null) {
                    "Variación frente al periodo anterior: sin datos del periodo anterior"
                } else {
                    "Variación frente al periodo anterior: " + String.format(ES, "%+.1f %%", variation) +
                        " (antes ${stats.previousTotal})"
                },
            )
        }
    }

    Text("Comensales por tipo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    for (share in stats.byType) {
        Text("${share.typeName}: ${share.count} (" + String.format(ES, "%.1f %%", share.percent) + ")")
    }

    Text(
        if (stats.period is Period.YearPeriod) "Comensales por mes" else "Comensales por día",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    BarChart(stats)

    DishRanking("Primeros servidos", stats.firsts)
    DishRanking("Segundos servidos", stats.seconds)
}

@Composable
private fun DishRanking(title: String, dishes: List<DishCount>) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (dishes.isEmpty()) Text("Ninguno.")
    for (dish in dishes) Text("${dish.count} · ${dish.name}")
}

@Composable
private fun BarChart(stats: Statistics) {
    val bars = stats.bars
    val yearly = stats.period is Period.YearPeriod
    val max = (bars.maxOfOrNull { it.value } ?: 0).coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline
    val dayFormat = DateTimeFormatter.ofPattern("d", ES)
    val summary = "Gráfico de barras. " + bars.filter { it.value > 0 }.joinToString(". ") { bar ->
        val name = if (yearly) bar.start.month.getDisplayName(TextStyle.FULL, ES) else bar.start.shortLabel()
        "$name: ${bar.value}"
    }

    Column {
        Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = summary }) {
            val slot = size.width / bars.size
            val barWidth = slot * 0.7f
            bars.forEachIndexed { index, bar ->
                val height = size.height * bar.value / max
                drawRect(
                    color = barColor,
                    topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - height),
                    size = Size(barWidth, height),
                )
            }
            drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (yearly) "Ene" else bars.first().start.format(dayFormat),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
            )
            Text("Máximo: $max", style = MaterialTheme.typography.labelSmall)
            Text(
                if (yearly) "Dic" else bars.last().start.format(dayFormat),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}
