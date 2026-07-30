package com.wolfeleo2.thingy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wolfeleo2.thingy.data.MAX_COMMENT_CHARS
import com.wolfeleo2.thingy.data.SpaceComment
import com.wolfeleo2.thingy.lib.formatItemDate
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Comments the user hasn't read: someone else's, newer than the read cursor.
 *
 * Own comments are never unread — you wrote them. A null createdAt is a write the server hasn't
 * acked yet, which can only be your own, so treating it as epoch is safe rather than merely
 * convenient: it would fail the author check first anyway.
 */
internal fun unreadComments(
    comments: List<SpaceComment>,
    currentUserId: String?,
    lastSeenAt: Long,
): List<SpaceComment> = comments.filter {
    it.userId != currentUserId && (it.createdAt?.time ?: 0L) > lastSeenAt
}

/**
 * The comment thread for a space, as a bottom sheet.
 *
 * A sheet rather than part of the grid on purpose: comments and saved items are different kinds of
 * thing, and interleaving them would both bury the content and read as if the comments *were*
 * items. This keeps the grid purely what's been saved.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SpaceCommentsSheet(
    comments: List<SpaceComment>,
    currentUserId: String?,
    /** Space owner may delete anyone's comment — they're the one left tidying up after a departure. */
    isOwner: Boolean,
    onPost: (String) -> Unit,
    onDelete: (SpaceComment) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    // Newest is at the bottom, so open on it and follow new arrivals — a thread that opens on its
    // oldest line makes you scroll to find out what's new. The first jump is instant (there's
    // nothing to animate away from); later arrivals scroll, so a comment landing mid-read reads as
    // movement rather than a jump cut.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(comments.size) {
        if (comments.isEmpty()) return@LaunchedEffect
        if (opened) listState.animateScrollToItem(comments.lastIndex)
        else { listState.scrollToItem(comments.lastIndex); opened = true }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: Expressive emblem + title + count, matching the members sheet next door.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(48.dp).clip(rememberMaterialShape(MaterialShapes.Cookie9Sided))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Comments", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (comments.isEmpty()) "No one's said anything yet"
                        else "${comments.size} ${if (comments.size == 1) "comment" else "comments"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (comments.isEmpty()) {
                ThingyEmptyState(
                    shape = MaterialShapes.Clover4Leaf,
                    icon = Icons.Filled.Forum,
                    title = "Nothing here yet",
                    message = "Say why something's saved, or what everyone's collecting.",
                    // Weighted, not a fixed height: ThingyEmptyState fills whatever it's given, and
                    // a Column measures unweighted children first — so a fixed height here was
                    // taken before the composer, which then got squeezed into the remainder. As the
                    // only weighted child it's measured last and absorbs the leftover instead.
                    // Still capped, or an empty thread would open as a full-height sheet.
                    modifier = Modifier.heightIn(max = 260.dp).weight(1f, fill = false),
                )
            } else {
                LazyColumn(
                    state = listState,
                    // Takes what's left after header and composer rather than a magic dp cap, so a
                    // short thread hugs its content and a long one can't push the input off screen.
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(comments, key = { it.id }) { comment ->
                        CommentRow(
                            comment = comment,
                            mine = comment.userId == currentUserId,
                            canDelete = isOwner || comment.userId == currentUserId,
                            onDelete = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDelete(comment)
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    // Capped at the length the security rule enforces, so an over-long comment is
                    // impossible to type rather than silently rejected by the server after posting.
                    onValueChange = { if (it.length <= MAX_COMMENT_CHARS) draft = it },
                    placeholder = { Text("Add a comment…") },
                    shape = OutlinedTextFieldDefaults.roundedShape,
                    maxLines = 4,
                    // Only nag near the ceiling; a counter on an empty field is noise.
                    supportingText = if (draft.length > MAX_COMMENT_CHARS - 200) {
                        { Text("${MAX_COMMENT_CHARS - draft.length} left") }
                    } else null,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = { onPost(draft); draft = "" },
                    enabled = draft.isNotBlank(),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post comment")
                }
            }
        }
    }
}

/**
 * One line of the thread, in a container like the members sheet's rows — naked text on the sheet
 * surface gave a thread no structure to scan.
 *
 * [mine] tints it and renames the author to "You": in a shared thread the useful distinction is
 * yours-vs-theirs, and your own display name is the one name you never need told.
 */
@Composable
private fun CommentRow(
    comment: SpaceComment,
    mine: Boolean,
    canDelete: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (mine) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MemberAvatar(comment.authorPhotoUrl, size = 36.dp)
            Column(Modifier.weight(1f).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (mine) "You" else comment.authorName ?: "Someone",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // createdAt is null until the write is acked — "just now" beats an empty gap.
                    // Derived from the container's own content color, since the row has two.
                    Text(
                        comment.createdAt?.let { formatItemDate(it.time) } ?: "just now",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (canDelete) {
                // Sized by the icon, not the button: IconButton keeps its 48dp touch target, which
                // the old .size(32.dp) was shrinking below the accessible minimum.
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete comment",
                        tint = LocalContentColor.current.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Top-bar entry point into the thread. The count is the only ambient signal there is — without
 *  push notifications, nothing else tells you a co-member wrote something. */
@Composable
fun CommentsAction(count: Int, onClick: () -> Unit) {
    BadgedBox(
        badge = { if (count > 0) Badge { Text(if (count > 99) "99+" else "$count") } },
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        AppBarAction(icon = Icons.Filled.Forum, contentDescription = "Comments", onClick = onClick)
    }
}

/**
 * Floating teaser for the most recent unread comment. Wrap-width and bottom-start so it never
 * reaches the FAB; the caller animates it in and out.
 *
 * **Swiped away rather than X'd.** A transient floating surface already has a native gesture for
 * "not now", and a 48dp dismiss button made dismissal as visually heavy as the comment itself.
 * Swipe isn't reachable from TalkBack, so it's mirrored as a custom accessibility action — the
 * gesture is the only way to dismiss, so it has to exist twice.
 *
 * **Colored from the space's own [seedColor]** (`Space.shelfColor`, already computed for the shelf
 * layout) so it reads as belonging to the space it floats over. Its previous `inverseSurface` is
 * the *snackbar* role, which made a persistent, tappable surface look like a toast about to time
 * out.
 *
 * **Dismissing marks the whole backlog read, not just this comment.** [comment] is the *newest*
 * unread one, and the caller tracks reading with a timestamp cursor — so clearing up to it
 * necessarily clears everything older, and the badge goes to zero. That's inherent to a cursor;
 * per-comment dismissal would need a set of dismissed ids alongside it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LatestCommentPill(
    comment: SpaceComment,
    seedColor: Long?,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val scheme = remember(seedColor, isDark) { seedColor?.let { seedColorScheme(it.toInt(), isDark) } }
    val container = scheme?.primaryContainer ?: MaterialTheme.colorScheme.primaryContainer
    val onContainer = scheme?.onPrimaryContainer ?: MaterialTheme.colorScheme.onPrimaryContainer

    val scope = rememberCoroutineScope()
    // Keyed on the comment: a new arrival re-centers a half-dragged pill, and the flung-off-screen
    // offset of a dismissed one can't linger and render the next teaser invisible.
    val offsetX = remember(comment.id) { Animatable(0f) }
    val commitPx = with(LocalDensity.current) { 96.dp.toPx() }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pillPress",
    )

    Surface(
        color = container,
        contentColor = onContainer,
        shape = CircleShape,
        // Floats over a scrolling grid — without a shadow it tangles with the cards behind it.
        shadowElevation = 6.dp,
        modifier = modifier
            .graphicsLayer {
                translationX = offsetX.value
                scaleX = scale
                scaleY = scale
                // Fades as it goes, so a half-committed swipe reads as still reversible.
                alpha = 1f - (abs(offsetX.value) / (commitPx * 2f)).coerceIn(0f, 0.85f)
            }
            .pointerInput(comment.id) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, drag ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + drag) }
                    },
                    onDragEnd = {
                        if (abs(offsetX.value) > commitPx) {
                            val exit = if (offsetX.value > 0) size.width * 1.5f else -size.width * 1.5f
                            scope.launch { offsetX.animateTo(exit); onDismiss() }
                        } else {
                            scope.launch { offsetX.animateTo(0f) }
                        }
                    },
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = "Open comments",
                onClick = onOpen,
            )
            .semantics {
                role = Role.Button
                customActions = listOf(CustomAccessibilityAction("Dismiss") { onDismiss(); true })
            },
    ) {
        // A newer comment rolls up into place instead of swapping under the reader's eyes.
        AnimatedContent(
            targetState = comment,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
            },
            label = "latestComment",
        ) { c ->
            Row(
                Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemberAvatar(c.authorPhotoUrl, size = 36.dp)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        c.authorName ?: "New comment",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        c.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = onContainer.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
