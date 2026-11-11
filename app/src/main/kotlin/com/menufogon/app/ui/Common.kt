package com.menufogon.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menufogon.app.MenuFogonApp
import com.menufogon.core.model.APP_ZONE
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

val ES: Locale = Locale.forLanguageTag("es-ES")

private val monthFormat = DateTimeFormatter.ofPattern("LLLL 'de' yyyy", ES)
private val longDateFormat = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", ES)
private val shortDateFormat = DateTimeFormatter.ofPattern("EEE d MMM", ES)
private val dateTimeFormat = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", ES)

fun today(): LocalDate = LocalDate.now(APP_ZONE)
fun YearMonth.label(): String = format(monthFormat).replaceFirstChar { it.titlecase(ES) }
fun LocalDate.longLabel(): String = format(longDateFormat).replaceFirstChar { it.titlecase(ES) }
fun LocalDate.shortLabel(): String = format(shortDateFormat)
fun Long.dateTimeLabel(): String = Instant.ofEpochMilli(this).atZone(APP_ZONE).format(dateTimeFormat)
fun Instant.dateTimeLabel(): String = atZone(APP_ZONE).format(dateTimeFormat)

/** Creates a ViewModel that receives the application container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (MenuFogonApp) -> VM): VM =
    viewModel(factory = viewModelFactory { initializer { create(this[APPLICATION_KEY] as MenuFogonApp) } })

/** "< noviembre de 2026 >" with accessible previous and next buttons. */
@Composable
fun Switcher(
    label: String,
    previousDescription: String,
    nextDescription: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = previousDescription)
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = nextDescription)
        }
    }
}
