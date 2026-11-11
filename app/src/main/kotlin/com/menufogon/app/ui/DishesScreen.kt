package com.menufogon.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.menufogon.app.data.MenuRepository
import com.menufogon.core.model.Dish
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DishesViewModel(private val repository: MenuRepository) : ViewModel() {
    val dishes: StateFlow<List<Dish>> = repository.observeDishes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun merge(keepId: Long, removeId: Long) {
        viewModelScope.launch { repository.mergeDishes(keepId, removeId) }
    }
}

@Composable
fun DishesScreen(viewModel: DishesViewModel, contentPadding: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val dishes by viewModel.dishes.collectAsStateWithLifecycle()
    // Id of the dish chosen first (the one that will be removed), then the one to keep.
    var removeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var keepId by rememberSaveable { mutableStateOf<Long?>(null) }
    val toRemove = dishes.firstOrNull { it.id == removeId }
    val toKeep = dishes.firstOrNull { it.id == keepId }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onBack) { Text("‹ Volver a Ajustes") }
                Text("Platos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    when {
                        dishes.isEmpty() -> "Todavía no hay platos. Aparecen al cargar un menú en Meses."
                        toRemove != null -> "Ahora toca el plato con el que quieres unir «${toRemove.displayName}»."
                        else -> "${dishes.size} platos. Si un plato aparece dos veces con nombres distintos, pulsa Fusionar en uno de ellos."
                    },
                )
                if (toRemove != null) {
                    OutlinedButton(onClick = { removeId = null }) { Text("Cancelar fusión") }
                }
            }
        }
        items(dishes, key = { it.id }) { dish ->
            val selected = dish.id == removeId
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = if (selected) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(dish.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (dish.ingredients.isBlank()) "Ingredientes: sin datos" else "Ingredientes: ${dish.ingredients}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (dish.allergens.isBlank()) "Alérgenos: ninguno declarado" else "Alérgenos: ${dish.allergens}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when {
                        toRemove == null -> TextButton(onClick = { removeId = dish.id }) { Text("Fusionar con otro…") }
                        !selected -> TextButton(onClick = { keepId = dish.id }) { Text("Unir con este") }
                    }
                }
            }
        }
    }

    if (toRemove != null && toKeep != null) {
        AlertDialog(
            onDismissRequest = { keepId = null },
            title = { Text("Fusionar platos") },
            text = {
                Text(
                    "«${toRemove.displayName}» y «${toKeep.displayName}» pasarán a ser un solo plato. " +
                        "¿Qué nombre quieres conservar? Los registros ya guardados no cambian.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.merge(keepId = toKeep.id, removeId = toRemove.id)
                    removeId = null
                    keepId = null
                }) { Text(toKeep.displayName) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.merge(keepId = toRemove.id, removeId = toKeep.id)
                    removeId = null
                    keepId = null
                }) { Text(toRemove.displayName) }
            },
        )
    }
}
