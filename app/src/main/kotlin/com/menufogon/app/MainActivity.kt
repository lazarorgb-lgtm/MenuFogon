package com.menufogon.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.menufogon.app.ui.CalendarScreen
import com.menufogon.app.ui.CalendarViewModel
import com.menufogon.app.ui.DishesScreen
import com.menufogon.app.ui.DishesViewModel
import com.menufogon.app.ui.MonthsScreen
import com.menufogon.app.ui.MonthsViewModel
import com.menufogon.app.ui.PrepareScreen
import com.menufogon.app.ui.PrepareViewModel
import com.menufogon.app.ui.SettingsScreen
import com.menufogon.app.ui.SettingsViewModel
import com.menufogon.app.ui.StatsScreen
import com.menufogon.app.ui.StatsViewModel
import com.menufogon.app.ui.appViewModel

enum class Tab(val label: String, val icon: ImageVector) {
    CALENDAR("Calendario", Icons.Filled.Home),
    MONTHS("Meses", Icons.Filled.DateRange),
    PREPARE("Preparar", Icons.AutoMirrored.Filled.List),
    STATS("Estadísticas", Icons.Filled.Info),
    SETTINGS("Ajustes", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {

    /** A PDF received through "Compartir", waiting to be handed to the Meses screen. */
    private var sharedPdf by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            MenuFogonTheme {
                App(sharedPdf = sharedPdf, onSharedConsumed = { sharedPdf = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "application/pdf") return
        sharedPdf = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}

@Composable
private fun MenuFogonTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFFFB4AB), secondary = Color(0xFFE7BDB8), tertiary = Color(0xFFDEC48C))
    } else {
        lightColorScheme(primary = Color(0xFFB3261E), secondary = Color(0xFF775651), tertiary = Color(0xFF705C2E))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun App(sharedPdf: Uri?, onSharedConsumed: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.CALENDAR) }
    var showDishes by rememberSaveable { mutableStateOf(false) }

    val monthsViewModel = appViewModel { MonthsViewModel(it.repository, it.pdfExtractor) }
    val calendarViewModel = appViewModel { CalendarViewModel(it.repository, it.backupPrefs) }
    val prepareViewModel = appViewModel { PrepareViewModel(it.repository) }
    val statsViewModel = appViewModel { StatsViewModel(it.repository) }
    val dishesViewModel = appViewModel { DishesViewModel(it.repository) }
    val settingsViewModel = appViewModel { SettingsViewModel(it.repository, it.backupPrefs, it.contentResolver) }

    LaunchedEffect(sharedPdf) {
        if (sharedPdf != null) {
            monthsViewModel.receiveShared(sharedPdf)
            onSharedConsumed()
            tab = Tab.MONTHS
            showDishes = false
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                for (item in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            tab = item
                            showDishes = false
                            if (item == Tab.PREPARE) prepareViewModel.refresh()
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { inner ->
        val direction = LocalLayoutDirection.current
        val padding = PaddingValues(
            start = inner.calculateStartPadding(direction),
            end = inner.calculateEndPadding(direction),
            top = inner.calculateTopPadding() + 8.dp,
            bottom = inner.calculateBottomPadding() + 16.dp,
        )
        when (tab) {
            Tab.CALENDAR -> CalendarScreen(
                viewModel = calendarViewModel,
                contentPadding = padding,
                onGoToMonths = { month ->
                    monthsViewModel.showMonth(month)
                    tab = Tab.MONTHS
                },
                onPrepare = { date ->
                    prepareViewModel.setDate(date)
                    tab = Tab.PREPARE
                },
            )
            Tab.MONTHS -> MonthsScreen(monthsViewModel, padding)
            Tab.PREPARE -> PrepareScreen(prepareViewModel, padding)
            Tab.STATS -> StatsScreen(statsViewModel, padding)
            Tab.SETTINGS -> if (showDishes) {
                DishesScreen(dishesViewModel, padding, onBack = { showDishes = false })
            } else {
                SettingsScreen(settingsViewModel, padding, onOpenDishes = { showDishes = true })
            }
        }
    }
}
