package com.spacecorps.oam.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Mira the innkeeper: an NPC on Gemini Nano (or a scripted stand-in) that
 * checks the menu and takes your gold through real tool calls.
 */
class MainActivity : ComponentActivity() {
    private val tavern: TavernViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val state by tavern.ui.collectAsState()
                TavernScreen(
                    state = state,
                    onSelectBackend = tavern::selectBackend,
                    onDownload = tavern::downloadModel,
                    onSay = tavern::say,
                    onCancel = tavern::cancelTurn,
                )
            }
        }
    }
}
