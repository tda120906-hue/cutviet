package com.cutviet.editor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.cutviet.editor.ui.editor.EditorScreen
import com.cutviet.editor.ui.home.HomeScreen
import com.cutviet.editor.ui.theme.CutVietTheme

class MainActivity : ComponentActivity() {
    /** Media shared into the app from another app; consumed by the home screen. */
    private val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            CutVietTheme {
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
