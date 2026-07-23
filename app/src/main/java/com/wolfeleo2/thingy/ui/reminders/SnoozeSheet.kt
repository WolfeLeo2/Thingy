package com.wolfeleo2.thingy.ui.reminders

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlin.math.roundToInt
import kotlin.math.sin

private data class NudgeStep(
    val title: String,
    val subtitle: String,
    val calculateTime: () -> Long
)

/** Sky look derived from the target hour, hardcoded rather than the app's dynamic theme so it always reads as an actual sky. */
private enum class SkyPeriod(val bg: Color, val arc: Color, val iconTint: Color, val icon: ImageVector) {
    DAWN(Color(0xFFFFCC80), Color(0xFFFB8C00), Color.White, Icons.Filled.WbTwilight),
    MORNING(Color(0xFF81D4FA), Color(0xFF0288D1), Color(0xFFFFF59D), Icons.Filled.WbSunny),
    AFTERNOON(Color(0xFFFFD54F), Color(0xFFFF8F00), Color(0xFFFFFDE7), Icons.Filled.WbSunny),
    DUSK(Color(0xFFFFAB91), Color(0xFFE64A19), Color.White, Icons.Filled.WbTwilight),
    NIGHT(Color(0xFF0F1642), Color(0xFF5C6BC0), Color(0xFFE8EAF6), Icons.Filled.NightsStay);

    companion object {
        fun forHour(hour: Int) = when (hour) {
            in 5..7 -> DAWN
            in 8..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..18 -> DUSK
            else -> NIGHT // 19h–4h: evening through pre-dawn reads as night, not dusk
        }
    }
}

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

    var sliderPosition by remember { mutableFloatStateOf(1f) } // Default to +2 hours
    var showCustomPicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

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

    val steps = remember {
        listOf(
            NudgeStep("+30 Mins", "Quick 30-minute breather ⚡") {
                System.currentTimeMillis() + 30 * 60 * 1000L
            },
            NudgeStep("+2 Hours", "Perfect for your afternoon break ☕") {
                System.currentTimeMillis() + 2 * 3600 * 1000L
            },
            NudgeStep("+5 Hours", "Later today focus window 🎯") {
                System.currentTimeMillis() + 5 * 3600 * 1000L
            },
            NudgeStep("Tonight", "Evening unwind & catch up 🌙") {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                }
                if (cal.before(Calendar.getInstance())) cal.add(Calendar.HOUR_OF_DAY, 4)
                // Still in the past (e.g. it's already past 11 PM) — push to tomorrow morning instead.
                if (cal.before(Calendar.getInstance())) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0)
                }
                cal.timeInMillis
            },
            NudgeStep("Tomorrow AM", "Fresh start tomorrow morning ☀️") {
                Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                }.timeInMillis
            },
            NudgeStep("This Weekend", "Saved for Saturday relaxation 🏖️") {
                Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
                    set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                    if (before(Calendar.getInstance())) add(Calendar.WEEK_OF_YEAR, 1)
                }.timeInMillis
            },
            NudgeStep("Custom...", "Choose exact date & time ⏱️") {
                System.currentTimeMillis() + 24 * 3600 * 1000L
            }
        )
    }

    val currentStepIndex = sliderPosition.roundToInt().coerceIn(0, steps.size - 1)
    val currentStep = steps[currentStepIndex]
    // Recomputed every recomposition (cheap Calendar math) so it never goes stale relative to "now"
    // if the sheet is left open past a time boundary (e.g. sitting on "Tonight" until after 7 PM).
    val calculatedTargetTime = currentStep.calculateTime()

    val targetHour = remember(calculatedTargetTime) {
        Calendar.getInstance().apply { timeInMillis = calculatedTargetTime }.get(Calendar.HOUR_OF_DAY)
    }
    val skyPeriod = SkyPeriod.forHour(targetHour)
    val skyBgColor by animateColorAsState(skyPeriod.bg, label = "skyBg")
    val skyArcColor by animateColorAsState(skyPeriod.arc, label = "skyArc")
    val skyIconTint by animateColorAsState(skyPeriod.iconTint, label = "skyIconTint")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            // Header: Expressive Emblem + Item Title
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
                Column(Modifier.weight(1f)) {
                    Text("Remind me later", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Snooze “${item.displayTitle()}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Sky Arc & Celestial Visualizer
            Surface(
                color = skyBgColor,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val marginDp = 24.dp
                    val markerSizeDp = 32.dp

                    Canvas(Modifier.fillMaxSize()) {
                        val marginPx = with(density) { marginDp.toPx() }
                        val topPx = with(density) { 16.dp.toPx() }
                        val h = size.height
                        val path = Path().apply {
                            moveTo(marginPx, h * 0.75f)
                            quadraticTo(size.width / 2f, topPx, size.width - marginPx, h * 0.75f)
                        }
                        drawPath(
                            path = path,
                            color = skyArcColor.copy(alpha = 0.5f),
                            style = Stroke(
                                width = 3f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                            )
                        )
                    }

                    // Celestial Marker along Arc — tracks the same margin/width the Canvas path uses above.
                    val progress = sliderPosition / (steps.size - 1).toFloat()
                    val sinY = sin(progress * Math.PI.toFloat())
                    val travel = (maxWidth - marginDp * 2 - markerSizeDp).coerceAtLeast(0.dp)
                    val markerX = marginDp + travel * progress

                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = markerX, y = -((sinY * 36).dp + 10.dp))
                            .size(markerSizeDp)
                            .clip(CircleShape)
                            .background(skyArcColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = skyPeriod.icon,
                            contentDescription = null,
                            tint = skyIconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Digital Clock Face & Playful Copy
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    targetState = formatSnoozeTime(calculatedTargetTime),
                    transitionSpec = { slideInVertically { height -> height } + fadeIn() togetherWith slideOutVertically { height -> -height } + fadeOut() },
                    label = "timeText"
                ) { formattedTime ->
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(4.dp))

                AnimatedContent(
                    targetState = currentStep.subtitle,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "subTitle"
                ) { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Slider(
                value = sliderPosition,
                onValueChange = {
                    val prevStep = sliderPosition.roundToInt()
                    sliderPosition = it
                    val newStep = it.roundToInt()
                    if (prevStep != newStep) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                valueRange = 0f..(steps.size - 1).toFloat(),
                steps = steps.size - 2,
                modifier = Modifier.fillMaxWidth()
            )

            // Shortcut Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                steps.forEachIndexed { idx, step ->
                    FilterChip(
                        selected = currentStepIndex == idx,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            sliderPosition = idx.toFloat()
                            if (idx == steps.size - 1) {
                                showCustomPicker = true
                            }
                        },
                        label = { Text(step.title, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Expressive Action Button
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (currentStepIndex == steps.size - 1) {
                        showCustomPicker = true
                    } else {
                        ReminderManager.scheduleSnooze(context, item.id, item.displayTitle(), item.description, calculatedTargetTime)
                        scope.launch {
                            settings.snoozeItem(item.id, calculatedTargetTime)
                            onSnoozed(calculatedTargetTime)
                            onDismiss()
                        }
                    }
                },
                shapes = expressiveButtonShapes(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (currentStepIndex == steps.size - 1) "Pick Date & Time…"
                    else "Set Nudge for ${currentStep.title} ✨"
                )
            }
        }
    }

    // Custom Date & Time Picker Dialogs
    if (showCustomPicker && !showTimePicker) {
        DatePickerDialog(
            onDismissRequest = { showCustomPicker = false },
            confirmButton = { TextButton(onClick = { showTimePicker = true }) { Text("Next") } },
            dismissButton = { TextButton(onClick = { showCustomPicker = false }) { Text("Cancel") } }
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
                TextButton(onClick = { showTimePicker = false; showCustomPicker = false }) { Text("Cancel") }
            }
        )
    }
}

fun formatSnoozeTime(epochMs: Long): String {
    val df = SimpleDateFormat("EEE, h:mm a", Locale.getDefault())
    return df.format(Date(epochMs))
}
