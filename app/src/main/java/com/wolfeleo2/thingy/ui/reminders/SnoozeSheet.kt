package com.wolfeleo2.thingy.ui.reminders

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wolfeleo2.thingy.data.Item
import com.wolfeleo2.thingy.data.SettingsRepository
import com.wolfeleo2.thingy.data.displayTitle
import com.wolfeleo2.thingy.reminders.ReminderManager
import com.wolfeleo2.thingy.ui.expressiveButtonShapes
import com.wolfeleo2.thingy.ui.rememberMaterialShape
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class SnoozeOption(
    val label: String,
    val icon: ImageVector,
    val calculateTime: () -> Long
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun SnoozeSheet(
    item: Item,
    settings: SettingsRepository,
    onDismiss: () -> Unit,
    onSnoozed: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var showCustomPicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    // Hoisted above both dialogs (not just the date-picker one) so the picked date survives
    // into the time-picker step — see the bug note below.
    val todayStartUtcMillis = remember {
        Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayStartUtcMillis
        },
    )

    val options = remember {
        listOf(
            SnoozeOption("Later Today (6 PM)", Icons.Filled.Schedule) {
                val sixPm = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 18)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // 6 PM already passed today — try a 4h nudge, but if that's ALSO in the
                // past (e.g. it's 11 PM), don't silently fire seconds from now: push to
                // tomorrow morning instead.
                if (sixPm.before(Calendar.getInstance())) {
                    sixPm.add(Calendar.HOUR_OF_DAY, 4)
                    if (sixPm.before(Calendar.getInstance())) {
                        sixPm.apply {
                            add(Calendar.DAY_OF_YEAR, 1)
                            set(Calendar.HOUR_OF_DAY, 9)
                            set(Calendar.MINUTE, 0)
                        }
                    }
                }
                sixPm.timeInMillis
            },
            SnoozeOption("Tomorrow (9 AM)", Icons.Filled.WbSunny) {
                Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            },
            SnoozeOption("This Weekend (Sat 9 AM)", Icons.Filled.Weekend) {
                Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(Calendar.getInstance())) {
                        add(Calendar.WEEK_OF_YEAR, 1)
                    }
                }.timeInMillis
            },
            SnoozeOption("Next Week (Mon 9 AM)", Icons.Filled.CalendarMonth) {
                Calendar.getInstance().apply {
                    add(Calendar.WEEK_OF_YEAR, 1)
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Expressive Emblem + Title
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(rememberMaterialShape(MaterialShapes.Cookie7Sided))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Alarm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text("Remind me later", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Snooze “${item.displayTitle()}”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Presets grid
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val targetTime = option.calculateTime()
                            ReminderManager.scheduleSnooze(context, item.id, item.displayTitle(), item.description, targetTime)
                            scope.launch {
                                settings.snoozeItem(item.id, targetTime)
                                onSnoozed(targetTime)
                                onDismiss()
                            }
                        },
                        label = { Text(option.label) },
                        leadingIcon = { Icon(option.icon, null, modifier = Modifier.size(16.dp)) }
                    )
                }

                FilterChip(
                    selected = showCustomPicker,
                    onClick = { showCustomPicker = true },
                    label = { Text("Custom Date & Time…") },
                    leadingIcon = { Icon(Icons.Filled.Schedule, null, modifier = Modifier.size(16.dp)) }
                )
            }
        }
    }

    // NOTE: these two dialogs used to both live inside `if (showCustomPicker)`, and "Next"
    // set showCustomPicker=false in the same click that set showTimePicker=true — so the time
    // picker's own `if` block never got a chance to render (it was nested inside a condition
    // that had just gone false). The custom-time flow silently died after "Next". Fixed by
    // gating each dialog on its own flag and only clearing showCustomPicker when the whole
    // custom flow ends (confirm or cancel), not on the date→time step transition.
    if (showCustomPicker && !showTimePicker) {
        DatePickerDialog(
            onDismissRequest = { showCustomPicker = false },
            confirmButton = {
                TextButton(onClick = { showTimePicker = true }) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val selectedDateMillis = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
        val timePickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0)

        val cal = Calendar.getInstance().apply {
            timeInMillis = selectedDateMillis
            set(Calendar.HOUR_OF_DAY, timePickerState.hour)
            set(Calendar.MINUTE, timePickerState.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        AlertDialog(
            onDismissRequest = { showTimePicker = false; showCustomPicker = false },
            title = { Text("Select time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    showTimePicker = false
                    showCustomPicker = false
                    val targetTime = cal.timeInMillis
                    ReminderManager.scheduleSnooze(context, item.id, item.displayTitle(), item.description, targetTime)
                    scope.launch {
                        settings.snoozeItem(item.id, targetTime)
                        onSnoozed(targetTime)
                        onDismiss()
                    }
                }) { Text("Set Reminder") }
            },
            dismissButton = {
                // Cancel here exits the whole custom flow rather than bouncing back to the
                // date step — going "back" instead of "cancel" would need its own affordance.
                TextButton(onClick = { showTimePicker = false; showCustomPicker = false }) { Text("Cancel") }
            }
        )
    }
}

fun formatSnoozeTime(epochMs: Long): String {
    val df = SimpleDateFormat("EEE, h:mm a", Locale.getDefault())
    return df.format(Date(epochMs))
}
