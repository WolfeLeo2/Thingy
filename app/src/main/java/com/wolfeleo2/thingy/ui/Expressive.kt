package com.wolfeleo2.thingy.ui

import android.graphics.drawable.shapes.OvalShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wolfeleo2.thingy.data.Classifier
import com.wolfeleo2.thingy.data.SpaceRepository
import kotlinx.coroutines.launch

/**
 * Shared Top App Bar action button: encapsulated tonal container with press-morphing shapes.
 * Use this globally to ensure consistency and a premium skeuomorphic feel.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppBarAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/** A Compose [Shape] from a Material [RoundedPolygon] (static — no morph). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class PolygonShape(private val morph: Morph) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress = 0f)
        path.transform(Matrix().apply { scale(size.width, size.height) })
        path.translate(size.center - path.getBounds().center)
        return Outline.Generic(path)
    }
}

@Composable
fun rememberMaterialShape(polygon: RoundedPolygon): Shape =
    remember(polygon) { PolygonShape(Morph(polygon, polygon)) }

/** Press-morphing button shapes (rounded → square on press), matching Login/Onboarding. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun expressiveButtonShapes(): ButtonShapes = ButtonShapes(ButtonDefaults.shape, ButtonDefaults.squareShape)

/** Shared empty/gate state: a Material-shape blob + emphasized title + message + optional CTA. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ThingyEmptyState(
    shape: RoundedPolygon,
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(rememberMaterialShape(shape))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, shapes = expressiveButtonShapes()) { Text(actionLabel) }
        }
    }
}

/**
 * Last row of the space pickers: taps swap it for an inline name field rather than navigating to
 * NewSpaceScreen, so whatever the caller is holding (a multi-select, an open item) survives the detour.
 * [onCreate] runs on the caller's scope — creating and then filing is one flow, and the picker
 * usually dismisses itself the moment it fires.
 */
@Composable
private fun NewSpaceRow(onCreate: (name: String) -> Unit) {
    var draft by remember { mutableStateOf<String?>(null) }
    val pending = draft
    if (pending == null) {
        Row(
            Modifier.fillMaxWidth().clickable { draft = "" }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("New space", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Filled.Add, contentDescription = "New space", tint = MaterialTheme.colorScheme.primary)
        }
    } else {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        // Blank stays open rather than silently doing nothing; a real name collapses back to the row,
        // which matters for the picker that survives the tap (ManageSpacesDialog).
        val submit = { pending.trim().takeIf { it.isNotEmpty() }?.let { onCreate(it); draft = null }; Unit }
        OutlinedTextField(
            value = pending,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            placeholder = { Text("Space name") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = submit) { Icon(Icons.Filled.Check, contentDescription = "Create") }
            },
        )
    }
}

/**
 * Multi-select counterpart to [ManageSpacesDialog]: pick one shelf, everything selected lands in it.
 * No per-space checkmarks — with N items a shelf is "some in, some out", which a toggle can't say.
 */
@Composable
fun AddManyToSpaceDialog(
    count: Int,
    spaceRepository: SpaceRepository,
    onPick: (spaceId: String) -> Unit,
    onCreateSpace: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spaces by remember { spaceRepository.spaces() }.collectAsStateWithLifecycle(emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $count ${if (count == 1) "thingy" else "thingies"} to…") },
        text = {
            Column {
                spaces.forEach { space ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(space.id) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(space.name, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                NewSpaceRow(onCreate = onCreateSpace)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shared Spaces [AlertDialog] to allow it to be shown across pages */
@Composable
fun ManageSpacesDialog(
    itemId: String,
    spaceRepository: SpaceRepository,
    classifier: Classifier,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val spaces by remember { spaceRepository.spaces() }.collectAsStateWithLifecycle(emptyList())
    val memberships by remember(itemId) { spaceRepository.membershipsForItem(itemId) }.collectAsStateWithLifecycle(emptyList())
    val live = memberships.filter { it.status != com.wolfeleo2.thingy.data.SpaceItemStatus.DISMISSED.wire }
    val bySpace = live.associateBy { it.spaceId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to space") },
        text = {
            Column {
                spaces.forEach { space ->
                    val inSpace = space.id in bySpace
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                if (inSpace) bySpace[space.id]?.let { spaceRepository.removeMembership(it.id) }
                                else { spaceRepository.addItemToSpace(itemId, space.id); classifier.steerItemForSpace(itemId, space.id) }
                            }
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(space.name, modifier = Modifier.weight(1f))
                        Icon(
                            if (inSpace) Icons.Filled.Check else Icons.Filled.Add,
                            contentDescription = if (inSpace) "In space" else "Add",
                            tint = if (inSpace) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Safe on this dialog's own scope — it stays composed after a tap, unlike the multi-select one.
                NewSpaceRow(onCreate = { name ->
                    scope.launch {
                        val id = spaceRepository.createSpace(name)
                        spaceRepository.addItemToSpace(itemId, id)
                        classifier.steerItemForSpace(itemId, id)
                    }
                })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
