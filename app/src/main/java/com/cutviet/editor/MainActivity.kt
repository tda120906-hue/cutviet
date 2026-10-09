package com.cutviet.editor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.cutviet.editor.ui.editor.EditorScreen
import com.cutviet.editor.ui.home.HomeScreen
import com.cutviet.editor.ui.theme.CutVietTheme

class MainActivity : ComponentActivity() {
    /** Media shared into the app from another app; consumed by the home screen. */
    private val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ always draws apps edge to edge, so keep the UI clear of the status bar,
        // navigation bar and keyboard ourselves.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            CutVietTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    var openProject by rememberSaveable { mutableStateOf<String?>(null) }
                    val current = openProject
                    if (current == null) {
                        HomeScreen(
                            sharedUris = sharedUris.value,
                            onSharedConsumed = { sharedUris.value = emptyList() },
                            onOpen = { openProject = it },
                        )
                    } else {
                        BackHandler { openProject = null }
                        EditorScreen(projectId = current, onBack = { openProject = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) sharedUris.value = uris
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelable(key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableList(key: String): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java) else getParcelableArrayListExtra(key))
            ?.toList().orEmpty()
}
