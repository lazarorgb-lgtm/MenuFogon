package com.menufogon.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.menufogon.app.BackupPrefs
import com.menufogon.app.BuildConfig
import com.menufogon.app.data.MenuRepository
import com.menufogon.core.backup.BackupCodec
import com.menufogon.core.backup.BackupData
import com.menufogon.core.backup.BackupReadResult
import com.menufogon.core.backup.BackupSummary
import com.menufogon.core.model.MenuType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class PendingImport(val summary: BackupSummary, val data: BackupData)

class SettingsViewModel(
    private val repository: MenuRepository,
    private val backupPrefs: BackupPrefs,
    private val resolver: ContentResolver,
) : ViewModel() {

    val types: StateFlow<List<MenuType>> = repository.observeTypes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lastExport: StateFlow<Long?> = backupPrefs.lastExport

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _pending = MutableStateFlow<PendingImport?>(null)
    val pending: StateFlow<PendingImport?> = _pending

    fun clearMessage() { _message.value = null }
    fun cancelImport() { _pending.value = null }
    fun renameType(id: Int, name: String) { viewModelScope.launch { repository.renameType(id, name) } }

    fun export(uri: Uri) {
        viewModelScope.launch {
            val now = Instant.now()
            val ok = runCatching {
                val text = BackupCodec.encode(repository.exportData(), now)
                withContext(Dispatchers.IO) {
                    val stream = resolver.openOutputStream(uri, "wt") ?: error("cannot open destination")
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }.isSuccess
            if (ok) backupPrefs.markExported(now.toEpochMilli())
            _message.value = if (ok) "Copia guardada." else "No se ha podido guardar la copia. Prueba en otra carpeta."
        }
    }

    /** Reads and validates the file. Nothing is changed until [confirmImport]. */
    fun readImport(uri: Uri) {
        viewModelScope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = resolver.openInputStream(uri) ?: error("cannot open file")
                    stream.use { input ->
                        // A real backup is small; refuse huge files instead of loading them.
                        val bytes = input.readNBytesCompat(MAX_BACKUP_BYTES + 1)
                        if (bytes.size > MAX_BACKUP_BYTES) error("too large")
                        bytes.toString(Charsets.UTF_8)
                    }
                }
            }.getOrNull()
            when (val result = text?.let(BackupCodec::decode)) {
                is BackupReadResult.Ok -> _pending.value = PendingImport(result.summary, result.file.data)
                is BackupReadResult.UnsupportedVersion -> _message.value =
                    "Copia no válida: está hecha con una versión de la app que esta versión no puede leer " +
                    "(formato ${result.version}). Actualiza la app e inténtalo de nuevo. No se ha cambiado nada."
                else -> _message.value =
                    "Copia no válida. El archivo debe ser el exportado por MenuCal, con extensión .json, " +
                    "sin editar a mano. No se ha cambiado nada."
            }
        }
    }

    fun confirmImport() {
        val pending = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch {
            val ok = runCatching { repository.replaceAll(pending.data) }.isSuccess
            _message.value = if (ok) "Copia importada." else "No se ha podido importar la copia. No se ha cambiado nada."
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (buffer.size() < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 50 * 1024 * 1024
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, contentPadding: PaddingValues, onOpenDishes: () -> Unit) {
    val types by viewModel.types.collectAsStateWithLifecycle()
    val lastExport by viewModel.lastExport.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.readImport(uri)
    }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Copia de seguridad", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Última copia: " + (lastExport?.dateTimeLabel() ?: "nunca"))
                Text(
                    "La copia guarda todos los datos en un archivo que eliges dónde dejar (Descargas, Drive…). " +
                        "No incluye los PDF originales.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { exporter.launch(BackupCodec.fileName(today())) }) { Text("Exportar copia") }
                OutlinedButton(onClick = {
                    // Some file apps label .json files as plain text or as a generic file.
                    importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }) { Text("Importar copia") }
                Text("Importar sustituye todos los datos actuales por los de la copia.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Platos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        OutlinedButton(onClick = onOpenDishes) { Text("Ver y fusionar platos") }

        Text("Nombres de los menús", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        for (type in types) {
            var text by remember(type.id, type.name) { mutableStateOf(type.name) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(30) },
                    label = { Text("Menú ${type.id}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { viewModel.renameType(type.id, text) },
                    enabled = text.isNotBlank() && text.trim() != type.name,
                ) { Text("Guardar") }
            }
        }

        Text("MenuFogon · versión ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    }

    val summary = pending?.summary
    if (summary != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Importar copia") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Fecha de la copia: ${summary.exportedAt.dateTimeLabel()}")
                    Text("Meses: ${summary.months}")
                    Text("Días con menú: ${summary.daysWithMenu}")
                    Text("Registros diarios: ${summary.entries}")
                    Text("Si confirmas, se sustituyen TODOS los datos actuales por los de esta copia.")
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmImport) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text("Cancelar") } },
        )
    }

    val text = message
    if (text != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("Aceptar") } },
        )
    }
}
