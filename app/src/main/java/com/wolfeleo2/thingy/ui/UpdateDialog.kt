package com.wolfeleo2.thingy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wolfeleo2.thingy.data.AppUpdate
import com.wolfeleo2.thingy.data.UpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(update: AppUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val checker = remember { UpdateChecker(context) }
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Update available — v${update.version}") },
        text = {
            Column {
                Text(
                    update.notes.ifBlank { "No release notes provided." },
                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                )
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    downloading = true
                    error = null
                    downloadJob = scope.launch {
                        runCatching {
                            val file = checker.download(update)
                            checker.install(file)
                            onDismiss()
                        }.onFailure { error = "Download failed: ${it.message}" }
                        downloading = false
                    }
                },
            ) { Text(if (downloading) "Downloading…" else "Update") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (downloading) {
                    downloadJob?.cancel()
                    downloading = false
                } else {
                    onDismiss()
                }
            }) { Text(if (downloading) "Cancel" else "Dismiss") }
        },
    )
}
