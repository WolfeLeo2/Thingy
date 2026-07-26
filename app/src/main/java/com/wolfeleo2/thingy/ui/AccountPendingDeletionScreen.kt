package com.wolfeleo2.thingy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Date
import java.util.concurrent.TimeUnit

private const val RETENTION_DAYS = 30

/**
 * Blocks the app entirely while an account is scheduled for deletion — surfaced instead of the
 * normal Home/Onboarding root whenever the signed-in user has a pending accountDeletions row.
 * The actual purge runs 30 days later via tools/account-purge, not from this screen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountPendingDeletionScreen(
    requestedAt: Date,
    onCancelDeletion: () -> Unit,
    onSignOut: () -> Unit,
) {
    val daysElapsed = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - requestedAt.time)
    val daysLeft = (RETENTION_DAYS - daysElapsed).coerceAtLeast(0)

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(rememberMaterialShape(MaterialShapes.Cookie9Sided))
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Text(
            "Account scheduled for deletion",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Text(
            if (daysLeft > 0) {
                "Your data will be permanently deleted in $daysLeft day${if (daysLeft == 1L) "" else "s"}. " +
                    "Cancel now to keep your account and everything in it."
            } else {
                "Your data is queued for permanent deletion. If it hasn't run yet, you can still cancel."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onCancelDeletion, shapes = expressiveButtonShapes()) {
            Text("Cancel deletion, keep my account")
        }
        TextButton(onClick = onSignOut) { Text("Sign out") }
    }
}
