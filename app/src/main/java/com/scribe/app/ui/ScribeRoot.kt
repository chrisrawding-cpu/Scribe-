package com.scribe.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.scribe.app.NavEvent
import com.scribe.app.recording.Recorder
import com.scribe.app.ui.ask.AskScreen
import com.scribe.app.ui.detail.DetailScreen
import com.scribe.app.ui.digest.DigestScreen
import com.scribe.app.ui.library.LibraryScreen
import com.scribe.app.ui.recorder.RecorderScreen
import com.scribe.app.ui.settings.SettingsScreen
import com.scribe.app.ui.settings.TemplatesScreen
import com.scribe.app.ui.tasks.TasksScreen
import kotlinx.coroutines.flow.SharedFlow

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "Library", Icons.Filled.GraphicEq),
    Tab("tasks", "Tasks", Icons.Filled.Checklist),
    Tab("ask", "Ask AI", Icons.Filled.AutoAwesome),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun ScribeRoot(events: SharedFlow<NavEvent>, consumed: () -> Unit) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) {
            Recorder.start(ctx)
            nav.navigate("recorder") { launchSingleTop = true }
        }
    }
    val startRecording: () -> Unit = {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            Recorder.start(ctx)
            nav.navigate("recorder") { launchSingleTop = true }
        } else permLauncher.launch(needed.toTypedArray())
    }

    LaunchedEffect(Unit) {
        events.collect { e ->
            when (e) {
                NavEvent.Record -> startRecording()
                NavEvent.OpenRecorder -> nav.navigate("recorder") { launchSingleTop = true }
                is NavEvent.OpenRecording -> nav.navigate("detail/${e.id}")
            }
            consumed()
        }
    }

    Scaffold(
        bottomBar = {
            if (route in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.switchTab(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            composable("library") {
                LibraryScreen(
                    onOpen = { nav.navigate("detail/$it") },
                    onRecord = startRecording,
                    onOpenRecorder = { nav.navigate("recorder") { launchSingleTop = true } },
                    onDigest = { nav.navigate("digest") },
                    onSettings = { nav.switchTab("settings") },
                )
            }
            composable("tasks") { TasksScreen(onOpen = { nav.navigate("detail/$it") }) }
            composable("ask") { AskScreen() }
            composable("settings") { SettingsScreen(onTemplates = { nav.navigate("templates") }) }
            composable("templates") { TemplatesScreen(onBack = { nav.popBackStack() }) }
            composable("digest") { DigestScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("detail/$it") }) }
            composable("recorder") {
                RecorderScreen(
                    onClose = { nav.popBackStack() },
                    onSaved = { id ->
                        nav.navigate("detail/$id") { popUpTo("recorder") { inclusive = true } }
                    },
                )
            }
            composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                DetailScreen(id = e.arguments?.getString("id")!!, onBack = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
