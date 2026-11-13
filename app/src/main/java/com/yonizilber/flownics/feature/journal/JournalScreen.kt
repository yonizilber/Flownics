package com.yonizilber.flownics.feature.journal

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import com.yonizilber.flownics.domain.fit.LoadSummary
import com.yonizilber.flownics.data.garmin.ZonesInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import com.yonizilber.flownics.feature.garmin.GarminViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material.icons.outlined.Sync
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.calendar.CalendarSync
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.PlanStatus
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.plan.DoneItem
import com.yonizilber.flownics.feature.activity.displayTitle
import com.yonizilber.flownics.feature.activity.icon
import com.yonizilber.flownics.feature.activity.summary
import com.yonizilber.flownics.feature.history.list.HistoryScreen
import com.yonizilber.flownics.ui.component.formatDuration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * The journal: the week planned and done (default), or the full history list. A plan counts as
 * done as soon as a matching workout or activity is in the history.
 */
@Composable
fun JournalScreen(
    onOpenSession: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    onLogActivity: () -> Unit,
    onPlan: (LocalDate) -> Unit,
    onOpenPlan: (Long) -> Unit,
    onAskAi: () -> Unit,
    onOpenMuscle: (Muscle) -> Unit,
    onOpenGarmin: () -> Unit,
    garmin: GarminViewModel = hiltViewModel(),
) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val garminSettings by garmin.settings.collectAsStateWithLifecycle()
    val busy by garmin.busy.collectAsStateWithLifecycle()
    val message by garmin.message.collectAsStateWithLifecycle()
    val pickFit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(garmin::importFile) }
    // New runs from the watch show up without asking, at most every few hours.
    LaunchedEffect(Unit) { garmin.syncIfDue() }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf(R.string.journal_week, R.string.journal_month, R.string.journal_list).forEachIndexed { i, label ->
                SegmentedButton(selected = mode == i, onClick = { mode = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(label)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (garminSettings.hasKey) garmin.syncNow() else onOpenGarmin() }, enabled = !busy) {
                Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(if (garminSettings.hasKey) R.string.garmin_sync_now else R.string.garmin_connect), modifier = Modifier.padding(start = 6.dp))
            }
            TextButton(onClick = { pickFit.launch(arrayOf("*/*")) }, enabled = !busy) { Text(stringResource(R.string.fit_import)) }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp))
        }
        message?.let { m ->
            Card(
                colors = CardDefaults.cardColors(containerColor = if (m.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    m.openActivityId?.let { id -> TextButton(onClick = { garmin.dismiss(); onOpenActivity(id) }) { Text(stringResource(R.string.coach_proposal_open)) } }
                    TextButton(onClick = garmin::dismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
        if (mode == 0) {
            WeekView(onOpenSession, onOpenActivity, onLogActivity, onPlan, onOpenPlan)
        } else if (mode == 1) {
            MonthView(onOpenSession, onOpenActivity, onPlan, onOpenPlan)
        } else {
            HistoryScreen(
                onOpenSession = onOpenSession,
                onOpenActivity = onOpenActivity,
                onLogActivity = onLogActivity,
                onAskAi = onAskAi,
                onOpenMuscle = onOpenMuscle,
            )
        }
    }
}

@Composable
private fun WeekView(
    onOpenSession: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    onLogActivity: () -> Unit,
    onPlan: (LocalDate) -> Unit,
    onOpenPlan: (Long) -> Unit,
    viewModel: WeekViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val week = state ?: run {
        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    val today = LocalDate.now()
    val range = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "nav") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Arrows point the way time runs on screen; in RTL the icons mirror themselves.
                IconButton(onClick = viewModel::previous) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.journal_previous_week)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${week.start.format(range)} – ${week.start.plusDays(6).format(range)}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.journal_week_summary, week.done, week.planned) +
                            if (week.km > 0) " · " + stringResource(R.string.journal_km, String.format(Locale.getDefault(), "%.1f", week.km)) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::next) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.journal_next_week)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (today !in week.start..week.start.plusDays(6)) TextButton(onClick = viewModel::thisWeek) { Text(stringResource(R.string.journal_this_week)) }
                TextButton(onClick = onLogActivity) { Text(stringResource(R.string.activity_log)) }
                if (week.calendarOn) {
                    Icon(Icons.Outlined.EventAvailable, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.journal_synced), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        load?.let { (l, z) ->
            if (l.counted > 0) item(key = "load") { LoadCard(l, z) }
        }
        items(week.days, key = { it.date.toString() }) { day ->
            DayRow(day, isToday = day.date == today, isPast = day.date < today, workoutNames = week.workoutNames, onPlan = { onPlan(day.date) }, onOpenPlan = onOpenPlan, onOpenSession = onOpenSession, onOpenActivity = onOpenActivity)
        }
    }
}

@Composable
private fun DayRow(
    day: DayEntry,
    isToday: Boolean,
    isPast: Boolean,
    workoutNames: Map<Long, String>,
    onPlan: () -> Unit,
    onOpenPlan: (Long) -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = if (isToday) FontWeight.Bold else null)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            day.plans.forEach { (plan, done) -> PlanLine(plan, done, isPast, workoutNames, onClick = { onOpenPlan(plan.id) }) }
            day.unplanned.forEach { done -> DoneLine(done, onOpenSession, onOpenActivity) }
            if (day.plans.isEmpty() && day.unplanned.isEmpty()) {
                Text(stringResource(R.string.journal_rest_day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            }
        }
        IconButton(onClick = onPlan) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.journal_plan_day)) }
    }
}

@Composable
private fun PlanLine(plan: PlannedWorkout, done: DoneItem?, isPast: Boolean, workoutNames: Map<Long, String>, onClick: () -> Unit) {
    val (icon, tint, status) = when {
        done != null -> Triple(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(R.string.plan_status_done))
        plan.status == PlanStatus.SKIPPED -> Triple(Icons.Outlined.Close, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.plan_status_skipped))
        isPast -> Triple(Icons.Outlined.RadioButtonUnchecked, MaterialTheme.colorScheme.error, stringResource(R.string.plan_status_missed))
        else -> Triple(Icons.Outlined.RadioButtonUnchecked, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.plan_status_planned))
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(planTitle(plan, workoutNames), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                // Done: when it really happened, not when it was planned.
                listOfNotNull(
                    done?.let { java.time.Instant.ofEpochMilli(it.atMillis).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString() } ?: plan.time?.toString(),
                    plan.durationMinutes?.takeIf { done == null }?.let { formatDuration(it * 60) },
                    status,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (isPast && done == null && plan.status == PlanStatus.PLANNED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DoneLine(done: DoneItem, onOpenSession: (Long) -> Unit, onOpenActivity: (Long) -> Unit) {
    when (done) {
        is DoneItem.Workout -> Row(
            Modifier.fillMaxWidth().clickable { onOpenSession(done.session.id) }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FitnessCenter, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column {
                Text(done.session.workoutName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatDuration(done.session.durationSeconds.toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is DoneItem.Logged -> Row(
            Modifier.fillMaxWidth().clickable { onOpenActivity(done.activity.id) }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(done.activity.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column {
                Text(done.activity.displayTitle(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(done.activity.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun planTitle(plan: PlannedWorkout, workoutNames: Map<Long, String>): String = plan.title.ifBlank {
    when (plan.kind) {
        PlanKind.WORKOUT -> plan.workoutId?.let(workoutNames::get) ?: stringResource(R.string.plan_untitled)
        PlanKind.ACTIVITY -> stringResource(CalendarSync.typeLabel(plan.activityType))
    }
}

/**
 * A month at a glance: each day marked with what was planned and done (a dot per item: filled =
 * done, ring = planned, red ring = planned and not done). Tapping a day lists it below.
 */
@Composable
private fun MonthView(
    onOpenSession: (Long) -> Unit,
    onOpenActivity: (Long) -> Unit,
    onPlan: (LocalDate) -> Unit,
    onOpenPlan: (Long) -> Unit,
    viewModel: WeekViewModel = hiltViewModel(),
) {
    val state by viewModel.monthState.collectAsStateWithLifecycle()
    val month = state ?: run {
        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    val today = LocalDate.now()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val byDate = month.days.associateBy { it.date }
    val lead = (month.month.atDay(1).dayOfWeek.value - month.firstDay.value + 7) % 7
    val cells: List<LocalDate?> = List(lead) { null } + month.days.map { it.date }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "nav") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::previousMonth) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.journal_previous_month)) }
                Text(
                    month.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = viewModel::nextMonth) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.journal_next_month)) }
            }
        }
        item(key = "weekdays") {
            Row(Modifier.fillMaxWidth()) {
                (0..6).forEach { i ->
                    Text(
                        month.firstDay.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        cells.chunked(7).forEachIndexed { row, week ->
            item(key = "row$row") {
                Row(Modifier.fillMaxWidth()) {
                    (0..6).forEach { i ->
                        val date = week.getOrNull(i)
                        Box(Modifier.weight(1f).aspectRatio(0.9f).padding(2.dp)) {
                            if (date != null) {
                                val day = byDate.getValue(date)
                                val isSelected = selected == date.toString()
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                                        .clickable { selected = date.toString() }
                                        .padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "${date.dayOfMonth}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (date == today) FontWeight.Bold else null,
                                        color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    DayDots(day, isPast = date < today)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "legend") {
            Text(stringResource(R.string.journal_month_legend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        selected?.let { LocalDate.parse(it) }?.let(byDate::get)?.let { day ->
            item(key = "day") {
                DayRow(day, isToday = day.date == today, isPast = day.date < today, workoutNames = month.workoutNames, onPlan = { onPlan(day.date) }, onOpenPlan = onOpenPlan, onOpenSession = onOpenSession, onOpenActivity = onOpenActivity)
            }
        }
    }
}

@Composable
private fun DayDots(day: DayEntry, isPast: Boolean) {
    val done = MaterialTheme.colorScheme.primary
    val missed = MaterialTheme.colorScheme.error
    val planned = MaterialTheme.colorScheme.outline
    val dots = day.plans.map { (plan, d) ->
        when {
            d != null -> done to true
            plan.status == PlanStatus.SKIPPED -> planned to false
            isPast -> missed to false
            else -> planned to false
        }
    } + day.unplanned.map { done to true }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
        dots.take(4).forEach { (color, filled) ->
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .then(if (filled) Modifier.background(color) else Modifier.border(1.5.dp, color, CircleShape)),
            )
        }
    }
}

/**
 * Training load from heart rate: the last 7 days against the weekly average of the last 28, and a
 * bar per day. Said in words, with what it is based on.
 */
@Composable
private fun LoadCard(l: LoadSummary, z: ZonesInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.load_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.load_line, l.last7, l.weeklyAverage28) +
                    (l.ratio?.let { " · " + stringResource(R.string.load_ratio, String.format(Locale.getDefault(), "%.2f", it)) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            val peak = l.daily.maxOf { it.second }.coerceAtLeast(1)
            val bar = MaterialTheme.colorScheme.primary
            Row(
                Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = l.daily.joinToString { "${it.first.dayOfMonth}: ${it.second}" } },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Oldest left, today right: time runs left to right.
                l.daily.forEach { (_, v) ->
                    Box(Modifier.weight(1f).fillMaxHeight(fraction = (v.toFloat() / peak).coerceAtLeast(0.02f)).clip(RoundedCornerShape(2.dp)).background(if (v > 0) bar else bar.copy(alpha = 0.15f)))
                }
            }
            Text(
                stringResource(R.string.load_caption, l.counted, l.withoutHeartRate) +
                    if (z.maxFromRecordings) " " + stringResource(R.string.load_caption_max_estimated, z.zones.max) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
