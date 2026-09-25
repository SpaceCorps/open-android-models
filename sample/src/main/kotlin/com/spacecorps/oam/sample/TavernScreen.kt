package com.spacecorps.oam.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spacecorps.oam.ModelAvailability
import com.spacecorps.oam.mlkit.DownloadEvent

/** The whole tavern screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TavernScreen(
    state: TavernUiState,
    onSelectBackend: (Backend) -> Unit,
    onDownload: () -> Unit,
    onSay: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { CenterAlignedTopAppBar(title = { Text("The Sleeping Stag") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            StatusCard(state, onSelectBackend, onDownload)
            Spacer(Modifier.padding(4.dp))
            Conversation(state.lines, Modifier.weight(1f))
            if (state.suggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.suggestions) { suggestion ->
                        AssistChip(onClick = { onSay(suggestion) }, label = { Text(suggestion, maxLines = 1) })
                    }
                }
            }
            InputRow(state.responding, onSay, onCancel)
        }
    }
}

@Composable
private fun StatusCard(state: TavernUiState, onSelectBackend: (Backend) -> Unit, onDownload: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = state.backend == Backend.GEMINI_NANO,
                    onClick = { onSelectBackend(Backend.GEMINI_NANO) },
                    label = { Text("Gemini Nano") },
                )
                FilterChip(
                    selected = state.backend == Backend.SCRIPTED,
                    onClick = { onSelectBackend(Backend.SCRIPTED) },
                    label = { Text("Scripted") },
                )
                Spacer(Modifier.weight(1f))
                Text("${state.gold} gold", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(availabilityText(state), style = MaterialTheme.typography.bodyMedium)
            val download = state.download
            when {
                download != null -> {
                    val fraction = (download as? DownloadEvent.Progress)?.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                state.backend == Backend.GEMINI_NANO && state.availability == ModelAvailability.Downloadable ->
                    Button(onClick = onDownload) { Text("Download Gemini Nano") }
                state.backend == Backend.GEMINI_NANO && state.availability is ModelAvailability.Downloading ->
                    OutlinedButton(onClick = onDownload) { Text("Show download progress") }
            }
        }
    }
}

private fun availabilityText(state: TavernUiState): String {
    val model = state.modelName ?: "model"
    return when (val availability = state.availability) {
        null -> "Checking $model…"
        ModelAvailability.Available -> "$model is ready, on this device."
        ModelAvailability.Downloadable -> "Gemini Nano is supported here but not downloaded yet."
        is ModelAvailability.Downloading -> {
            val done = availability.bytesDownloaded
            val total = availability.totalBytes
            if (done != null && total != null && total > 0) "Downloading Gemini Nano: ${done * 100 / total}% of ${total / 1_000_000} MB"
            else "Gemini Nano is downloading…"
        }
        is ModelAvailability.Unavailable ->
            "Gemini Nano is unavailable (${availability.reason}). ${availability.detail.orEmpty()} Switch to Scripted to try the demo."
    }
}

@Composable
private fun Conversation(lines: List<ChatLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val last = lines.lastOrNull()
    LaunchedEffect(lines.size, (last as? ChatLine.Mira)?.text?.length) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(lines, key = { it.id }) { line -> Line(line) }
    }
}

@Composable
private fun Line(line: ChatLine) {
    val colors = MaterialTheme.colorScheme
    when (line) {
        is ChatLine.Player -> Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("you › ") }
                append(line.text)
            },
        )
        is ChatLine.Mira -> Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.primary)) { append("Mira › ") }
                append(line.text)
                if (line.streaming) withStyle(SpanStyle(color = colors.outline)) { append(" ▍") }
            },
        )
        is ChatLine.ToolCall -> Text(
            "⚙ ${line.name} ${line.arguments}",
            color = colors.tertiary,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 16.dp),
        )
        is ChatLine.ToolResult -> Text(
            "↳ ${line.text}",
            color = if (line.isError) colors.error else colors.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 28.dp),
        )
        is ChatLine.Notice -> Text(
            line.text,
            color = if (line.isProblem) colors.error else colors.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun InputRow(responding: Boolean, onSay: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Say something to Mira") },
            singleLine = true,
            enabled = !responding,
        )
        Spacer(Modifier.width(8.dp))
        if (responding) {
            OutlinedButton(onClick = onCancel) { Text("Stop") }
        } else {
            Button(
                onClick = {
                    onSay(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
            ) { Text("Say") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TavernScreenPreview() {
    MaterialTheme {
        TavernScreen(
            state = TavernUiState(
                backend = Backend.SCRIPTED,
                availability = ModelAvailability.Available,
                modelName = "scripted Mira",
                gold = 25,
                lines = listOf(
                    ChatLine.Notice(0, "Rain hammers the shutters of the Sleeping Stag."),
                    ChatLine.Player(1, "I'll take the rabbit stew, please."),
                    ChatLine.ToolCall(2, "take_order", "{\"item\":\"rabbit stew\"}"),
                    ChatLine.ToolResult(3, "{\"served\":\"rabbit stew\",\"paid_gold\":5,\"player_gold_left\":25}", isError = false),
                    ChatLine.Mira(4, "One rabbit stew coming right up, love.", streaming = true),
                ),
            ),
            onSelectBackend = {},
            onDownload = {},
            onSay = {},
            onCancel = {},
        )
    }
}
