package com.wolfeleo2.thingy.ui

import androidx.compose.runtime.Composable
import com.wolfeleo2.thingy.data.AppUpdate

@Composable
fun UpdateDialog(update: AppUpdate, onDismiss: () -> Unit) {
    UpdateSheet(update = update, onDismiss = onDismiss)
}
