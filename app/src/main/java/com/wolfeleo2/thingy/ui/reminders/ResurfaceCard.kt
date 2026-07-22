package com.wolfeleo2.thingy.ui.reminders

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.displayTitle
import com.wolfeleo2.thingy.ui.expressiveButtonShapes
import com.wolfeleo2.thingy.ui.extractPaletteSeed
import com.wolfeleo2.thingy.ui.previewModel
import com.wolfeleo2.thingy.ui.rememberMaterialShape
import com.wolfeleo2.thingy.ui.seedColorScheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ResurfaceCard(
    item: Item,
    onOpen: () -> Unit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    var seed by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(item.id) {
        val model = item.previewModel(context)
        if (model != null) {
            seed = extractPaletteSeed(context, model)
        }
    }

    val scheme = remember(seed, isDark) {
        seed?.let { seedColorScheme(it, isDark) }
    }
    val cardBg = scheme?.primaryContainer ?: MaterialTheme.colorScheme.secondaryContainer
    val cardFg = scheme?.onPrimaryContainer ?: MaterialTheme.colorScheme.onSecondaryContainer
    val primaryBtnColor = scheme?.primary ?: MaterialTheme.colorScheme.primary

    Surface(
        color = cardBg,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header: Expressive Badge + "On this day" label
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(rememberMaterialShape(MaterialShapes.Sunny))
                        .background(cardFg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        tint = cardBg,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "On this day / Remember this?",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = cardFg
                    )
                    Text(
                        "A save from your feed worth revisiting",
                        style = MaterialTheme.typography.bodySmall,
                        color = cardFg.copy(alpha = 0.75f)
                    )
                }
            }

            // Thumbnail + Content Preview Row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val url = item.imageUrl ?: item.heroImageUrl
                if (url != null) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        item.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = cardFg,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.description.isNullOrEmpty()) {
                        Text(
                            item.description.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = cardFg.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Action Buttons Row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = cardFg.copy(alpha = 0.8f))
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = onSnooze, shapes = expressiveButtonShapes()) {
                    Text("Remind me later")
                }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = onOpen,
                    shapes = expressiveButtonShapes()
                ) {
                    Text("Open")
                }
            }
        }
    }
}
