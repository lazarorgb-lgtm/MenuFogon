package com.menufogon.app

import android.app.Application
import android.content.Context
import com.menufogon.app.data.AppDatabase
import com.menufogon.app.data.MenuRepository
import com.menufogon.app.pdf.PdfTextExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Remembers when the last backup was exported. Not part of the database on purpose. */
class BackupPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getLong(KEY, 0L).takeIf { it > 0L })

    /** Epoch milliseconds of the last export, or null if a copy was never exported. */
    val lastExport: StateFlow<Long?> = state

    fun markExported(epochMs: Long) {
        prefs.edit().putLong(KEY, epochMs).apply()
        state.value = epochMs
    }

    private companion object {
        const val KEY = "lastExportEpochMs"
    }
}

class MenuFogonApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val repository: MenuRepository by lazy { MenuRepository(database) }
    val pdfExtractor: PdfTextExtractor by lazy { PdfTextExtractor(this) }
    val backupPrefs: BackupPrefs by lazy { BackupPrefs(this) }
}
