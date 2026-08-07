package com.wolfeleo2.thingy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.ItemType
import com.wolfeleo2.thingy.data.displayTitle

/**
 * The feed's type lens. Deliberately a fixed set rather than "types you currently own" — a menu
 * whose contents shift as you save and delete is harder to learn than one empty state.
 */
enum class TypeFilter(val label: String, val icon: ImageVector, val type: ItemType?) {
    ALL("All", Icons.Filled.GridView, null),
    IMAGES("Images", Icons.Filled.Image, ItemType.IMAGE),
    LINKS("Links", Icons.Filled.Link, ItemType.LINK),
    NOTES("Notes", Icons.Filled.Notes, ItemType.NOTE),
    VIDEOS("Videos", Icons.Filled.Videocam, ItemType.VIDEO),
    AUDIO("Voice", Icons.Filled.Mic, ItemType.AUDIO);

    /** Message for a filter that matched nothing, so an empty grid never reads as lost data. */
    val emptyMessage: String
        get() = if (this == ALL) "Nothing saved yet" else "No ${label.lowercase()} yet"
}

enum class SortField(val label: String) { DATE_SAVED("Date saved"), TITLE("Title") }

/**
 * A just-created item's `@ServerTimestamp createdAt` is still null while the write is in flight.
 * It is the *newest* thing in the library, so it must sort as such in both directions — reading a
 * null as epoch is what once made a freshly-saved item resurface as a "memory".
 */
private fun Item.sortStamp(): Long = createdAt?.time ?: Long.MAX_VALUE

internal fun applyFilterSort(
    items: List<Item>,
    filter: TypeFilter,
    field: SortField,
    ascending: Boolean,
): List<Item> {
    val kept = filter.type?.let { t -> items.filter { it.type == t.wire } } ?: items
    val comparator = when (field) {
        SortField.DATE_SAVED -> compareBy<Item> { it.sortStamp() }
        // Ties broken by date so equal titles keep a stable, meaningful order rather than an arbitrary one.
        SortField.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it: Item -> it.displayTitle() }
            .thenByDescending { it.sortStamp() }
    }
    return kept.sortedWith(if (ascending) comparator else comparator.reversed())
}

/**
 * Same transform over the (membership, item) pairs a space screen carries. Reorders by the item
 * and maps back, so the pure [applyFilterSort] above stays the single tested implementation.
 */
internal fun <M> List<Pair<M, Item>>.filterSort(
    filter: TypeFilter,
    field: SortField,
    ascending: Boolean,
): List<Pair<M, Item>> {
    val byId = associateBy { it.second.id }
    return applyFilterSort(map { it.second }, filter, field, ascending).mapNotNull { byId[it.id] }
}

/**
 * Type filter + sort controls. Stateless — every caller owns its own state, so filtering the home
 * feed doesn't silently filter a space you open afterwards.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilterSortBar(
    filter: TypeFilter,
    onFilter: (TypeFilter) -> Unit,
    field: SortField,
    ascending: Boolean,
    onSort: (SortField, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typeMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box {
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.LeadingButton(onClick = { typeMenu = true }) {
                        Icon(filter.icon, contentDescription = null, Modifier.size(SplitButtonDefaults.LeadingIconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(filter.label)
                    }
                },
                trailingButton = {
                    SplitButtonDefaults.TrailingButton(
                        checked = typeMenu,
                        onCheckedChange = { typeMenu = it },
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Filter by type",
                            Modifier
                                .size(SplitButtonDefaults.TrailingIconSize)
                                .graphicsLayer { rotationZ = if (typeMenu) 180f else 0f },
                        )
                    }
                },
            )
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                TypeFilter.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.label) },
                        leadingIcon = { Icon(t.icon, contentDescription = null) },
                        trailingIcon = { if (t == filter) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                        onClick = { typeMenu = false; onFilter(t) },
                    )
                }
            }
        }

        Box {
            IconButton(onClick = { sortMenu = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SortField.entries.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(f.label) },
                        trailingIcon = { if (f == field) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                        onClick = { sortMenu = false; onSort(f, ascending) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (ascending) "Ascending" else "Descending", color = MaterialTheme.colorScheme.primary) },
                    leadingIcon = {
                        Icon(
                            if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { sortMenu = false; onSort(field, !ascending) },
                )
            }
        }
    }
}
