package com.wolfeleo2.thingy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wolfeleo2.thingy.data.AppUpdate
import com.wolfeleo2.thingy.data.UpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HardLineBreak
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as CmText
import org.commonmark.parser.Parser

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateSheet(
    update: AppUpdate,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val checker = remember { UpdateChecker(context) }
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dlBytes by remember { mutableLongStateOf(0L) }
    var dlTotal by remember { mutableLongStateOf(0L) }

    ModalBottomSheet(
        onDismissRequest = { if (!downloading) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Expressive emblem badge + Version title
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(rememberMaterialShape(MaterialShapes.Cookie7Sided))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Update available",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Version v${update.version} is ready to install",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Release Notes Card
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "What's New",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    MarkdownText(
                        markdown = update.notes.ifBlank { "Performance improvements and bug fixes." },
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }

            if (downloading) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (dlTotal > 0) {
                        LinearWavyProgressIndicator(
                            progress = { (dlBytes.toFloat() / dlTotal).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    fun mb(b: Long) = "%.1f MB".format(b / 1_000_000.0)
                    Text(
                        if (dlTotal > 0) "Downloading — ${mb(dlBytes)} / ${mb(dlTotal)}" else "Downloading update package…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Action buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        if (downloading) {
                            downloadJob?.cancel()
                            downloading = false
                        } else {
                            onDismiss()
                        }
                    }
                ) {
                    Text(if (downloading) "Cancel" else "Later")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !downloading,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        downloading = true
                        error = null
                        dlBytes = 0L
                        dlTotal = 0L
                        downloadJob = scope.launch {
                            runCatching {
                                val file = checker.download(update) { done, total -> dlBytes = done; dlTotal = total }
                                checker.install(file)
                                onDismiss()
                            }.onFailure {
                                error = "Download failed: ${it.message}"
                            }
                            downloading = false
                        }
                    },
                    shapes = expressiveButtonShapes()
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (downloading) "Downloading…" else "Install Update")
                }
            }
        }
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val document = remember(markdown) {
        val parser = Parser.builder().build()
        parser.parse(markdown)
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var node = document.firstChild
        while (node != null) {
            RenderBlockNode(node, primaryColor, linkColor, onSurface, codeBg)
            node = node.next
        }
    }
}

@Composable
private fun RenderBlockNode(
    node: Node,
    primaryColor: Color,
    linkColor: Color,
    onSurface: Color,
    codeBg: Color
) {
    when (node) {
        is Heading -> {
            val style = when (node.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                text = buildInlineMarkdown(node, linkColor, codeBg),
                style = style,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }
        is Paragraph -> {
            Text(
                text = buildInlineMarkdown(node, linkColor, codeBg),
                style = MaterialTheme.typography.bodyMedium,
                color = onSurface
            )
        }
        is BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 8.dp)) {
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("•", style = MaterialTheme.typography.bodyMedium, color = primaryColor)
                            Column {
                                var child = item.firstChild
                                while (child != null) {
                                    RenderBlockNode(child, primaryColor, linkColor, onSurface, codeBg)
                                    child = child.next
                                }
                            }
                        }
                    }
                    item = item.next
                }
            }
        }
        is OrderedList -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 8.dp)) {
                var item = node.firstChild
                var index = node.startNumber
                while (item != null) {
                    if (item is ListItem) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("$index.", style = MaterialTheme.typography.bodyMedium, color = primaryColor, fontWeight = FontWeight.Bold)
                            Column {
                                var child = item.firstChild
                                while (child != null) {
                                    RenderBlockNode(child, primaryColor, linkColor, onSurface, codeBg)
                                    child = child.next
                                }
                            }
                        }
                        index++
                    }
                    item = item.next
                }
            }
        }
        is FencedCodeBlock -> {
            Surface(
                color = codeBg,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    text = node.literal.trimEnd(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = onSurface,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        is IndentedCodeBlock -> {
            Surface(
                color = codeBg,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    text = node.literal.trimEnd(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = onSurface,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        is BlockQuote -> {
            Row(
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(primaryColor, RoundedCornerShape(2.dp))
                )
                Column {
                    var child = node.firstChild
                    while (child != null) {
                        RenderBlockNode(child, primaryColor, linkColor, onSurface, codeBg)
                        child = child.next
                    }
                }
            }
        }
        else -> {
            var child = node.firstChild
            while (child != null) {
                RenderBlockNode(child, primaryColor, linkColor, onSurface, codeBg)
                child = child.next
            }
        }
    }
}

private fun buildInlineMarkdown(
    parent: Node,
    linkColor: Color,
    codeBg: Color
): AnnotatedString {
    return buildAnnotatedString {
        fun visit(node: Node) {
            var child = node.firstChild
            while (child != null) {
                when (child) {
                    is CmText -> append(child.literal)
                    is StrongEmphasis -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            visit(child)
                        }
                    }
                    is Emphasis -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            visit(child)
                        }
                    }
                    is Code -> {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBg,
                                fontSize = 13.sp
                            )
                        ) {
                            append(" ${child.literal} ")
                        }
                    }
                    is Link -> {
                        val destination = child.destination
                        val linkStyles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        withLink(LinkAnnotation.Url(url = destination, styles = linkStyles)) {
                            visit(child)
                        }
                    }
                    is SoftLineBreak, is HardLineBreak -> append("\n")
                    else -> visit(child)
                }
                child = child.next
            }
        }
        visit(parent)
    }
}
