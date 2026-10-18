package neth.iecal.questphone.app.screens.etc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import neth.iecal.questphone.core.utils.AppUsageSession
import neth.iecal.questphone.core.utils.UsageStatsHelper
import nethical.questphone.data.ScreentimeStat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class BreakdownChartMode {
    HOURLY_24H,
    DAILY_7_DAYS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUsageBreakdownScreen(
    stat: ScreentimeStat,
    selectedDateMillis: Long,
    isBlocked: Boolean,
    isIgnored: Boolean,
    adderPrice: Int = 0,
    removerPrice: Int = 50,
    adderOwned: Int = 0,
    removerOwned: Int = 0,
    onToggleBlocked: (String, String) -> Unit,
    onToggleIgnored: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val pm = context.packageManager

    val selectedLocalDate = remember(selectedDateMillis) {
        Instant.ofEpochMilli(selectedDateMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }

    var appLabel by remember(stat.packageName) { mutableStateOf(stat.packageName) }
    var appIconBitmap by remember(stat.packageName) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }

    var hourlyUsage by remember { mutableStateOf<List<Long>>(List(24) { 0L }) }
    var sevenDayUsage by remember { mutableStateOf<List<Pair<LocalDate, Long>>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<AppUsageSession>>(emptyList()) }
    var isLoadingBreakdown by remember { mutableStateOf(true) }
    var chartMode by remember { mutableStateOf(BreakdownChartMode.HOURLY_24H) }

    LaunchedEffect(stat.packageName, selectedLocalDate) {
        isLoadingBreakdown = true
        withContext(Dispatchers.IO) {
            try {
                val appInfo = pm.getApplicationInfo(stat.packageName, 0)
                appLabel = appInfo.loadLabel(pm).toString()
                appIconBitmap = appInfo.loadIcon(pm).toBitmap().asImageBitmap()
            } catch (_: Exception) {
                appLabel = stat.packageName
            }

            val helper = UsageStatsHelper(context)
            val computedHourly = helper.getAppHourlyUsageForDay(stat.packageName, selectedLocalDate)
            val finalHourly = if (computedHourly.sum() > 0L) {
                computedHourly
            } else if (stat.startTimes.isNotEmpty() && stat.totalTime > 0L) {
                // Fallback distribution across hours where app was launched
                val perLaunch = stat.totalTime / stat.startTimes.size
                val fallback = LongArray(24)
                stat.startTimes.forEach { zdt ->
                    val h = zdt.hour.coerceIn(0, 23)
                    fallback[h] += perLaunch
                }
                fallback.toList()
            } else {
                computedHourly
            }

            val computed7Days = helper.getAppDailyUsageForLast7Days(stat.packageName, selectedLocalDate)
            val computedSessions = helper.getAppSessionsForDay(stat.packageName, selectedLocalDate)

            withContext(Dispatchers.Main) {
                hourlyUsage = finalHourly
                sevenDayUsage = computed7Days
                sessions = computedSessions
                isLoadingBreakdown = false
            }
        }
    }

    val launchCount = maxOf(stat.startTimes.size, sessions.size)
    val avgSessionMillis = if (launchCount > 0) stat.totalTime / launchCount else stat.totalTime
    val longestSessionMillis = sessions.maxOfOrNull { it.durationMillis } ?: avgSessionMillis
    val peakHourIndex = hourlyUsage.indices.maxByOrNull { hourlyUsage[it] } ?: 0
    val peakHourValue = hourlyUsage.getOrElse(peakHourIndex) { 0L }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "App Usage Breakdown",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = formatDate(selectedDateMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("breakdown_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { openSystemAppInfo(context, stat.packageName) },
                        modifier = Modifier.testTag("breakdown_app_info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "App Info"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. App Header & Distracting App / Blocklist Control Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (appIconBitmap != null) {
                                Image(
                                    bitmap = appIconBitmap!!,
                                    contentDescription = appLabel,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = appLabel.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = appLabel,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stat.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (isBlocked) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    }
                                ) {
                                    Text(
                                        text = if (isBlocked) "In Distracting Apps (Blocked)" else "Unrestricted App",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isBlocked) {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(16.dp))

                        // Synchronized Distracting App / Blocklist Action Button
                        Button(
                            onClick = {
                                onToggleBlocked(stat.packageName, appLabel)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("toggle_distracting_app_button"),
                            colors = if (isBlocked) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        ) {
                            Icon(
                                imageVector = if (isBlocked) Icons.Default.Close else Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isBlocked) {
                                    if (removerOwned > 0) {
                                        "Remove from Blocklist (Use 1 Remover • Owned: $removerOwned)"
                                    } else {
                                        "Remove from Blocklist ($removerPrice Coins)"
                                    }
                                } else {
                                    when {
                                        adderPrice == 0 -> "Add to Distracting Apps / Blocklist (Free)"
                                        adderOwned > 0 -> "Add to Blocklist (Use 1 Adder • Owned: $adderOwned)"
                                        else -> "Add to Distracting Apps ($adderPrice Coins)"
                                    }
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onToggleIgnored(stat.packageName)
                                    val msg = if (!isIgnored) {
                                        "Ignored $appLabel from future usage totals"
                                    } else {
                                        "Restored $appLabel to usage totals"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("toggle_ignore_app_button")
                            ) {
                                Icon(
                                    imageVector = if (isIgnored) Icons.Default.Check else Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isIgnored) "Unignore in Stats" else "Ignore in Stats",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 2. Key Usage Metrics Grid
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricBreakdownCard(
                            title = "Total Screen Time",
                            value = formatTime(stat.totalTime),
                            subtitle = formatDetailedDuration(stat.totalTime),
                            modifier = Modifier.weight(1f)
                        )
                        MetricBreakdownCard(
                            title = "Times Opened",
                            value = "$launchCount",
                            subtitle = if (launchCount == 1) "1 session today" else "$launchCount sessions today",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricBreakdownCard(
                            title = "Avg. Session",
                            value = formatTime(avgSessionMillis),
                            subtitle = "Longest: ${formatTime(longestSessionMillis)}",
                            modifier = Modifier.weight(1f)
                        )
                        MetricBreakdownCard(
                            title = "Peak Hour",
                            value = if (peakHourValue > 0L) formatHourLabel(peakHourIndex) else "—",
                            subtitle = if (peakHourValue > 0L) "${formatTime(peakHourValue)} active" else "No peak recorded",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 3. Line Chart Card (Hourly 24h & 7-Day Trend)
            item {
                UsageLineChartCard(
                    isLoading = isLoadingBreakdown,
                    chartMode = chartMode,
                    onChartModeChange = { chartMode = it },
                    hourlyUsage = hourlyUsage,
                    sevenDayUsage = sevenDayUsage
                )
            }

            // 4. Time of Day Breakdown (Morning / Afternoon / Evening / Night)
            item {
                TimeOfDayBreakdownCard(hourlyUsage = hourlyUsage, totalTime = stat.totalTime)
            }

            // 5. Session / Launch Timeline Breakdown
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Activity Sessions & Launches",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Detailed timeline of when $appLabel was used",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (sessions.isNotEmpty()) {
                            val timeFormatter = remember {
                                DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())
                            }
                            sessions.take(25).forEachIndexed { idx, session ->
                                val startZdt = Instant.ofEpochMilli(session.startTimeMillis)
                                    .atZone(ZoneId.systemDefault())
                                val endZdt = Instant.ofEpochMilli(session.endTimeMillis)
                                    .atZone(ZoneId.systemDefault())

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "${startZdt.format(timeFormatter)} – ${endZdt.format(timeFormatter)}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = getPeriodOfDayLabel(startZdt.hour),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = formatDetailedDuration(session.durationMillis),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                if (idx < minOf(sessions.size, 25) - 1) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    )
                                }
                            }
                        } else if (stat.startTimes.isNotEmpty()) {
                            val timeFormatter = remember {
                                DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())
                            }
                            stat.startTimes.sortedDescending().take(25).forEachIndexed { idx, startTime ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Opened at ${startTime.format(timeFormatter)}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = getPeriodOfDayLabel(startTime.hour),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (idx < minOf(stat.startTimes.size, 25) - 1) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Total active time: ${formatDetailedDuration(stat.totalTime)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBreakdownCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UsageLineChartCard(
    isLoading: Boolean,
    chartMode: BreakdownChartMode,
    onChartModeChange: (BreakdownChartMode) -> Unit,
    hourlyUsage: List<Long>,
    sevenDayUsage: List<Pair<LocalDate, Long>>
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    val points: List<Pair<String, Float>> = remember(chartMode, hourlyUsage, sevenDayUsage) {
        when (chartMode) {
            BreakdownChartMode.HOURLY_24H -> {
                hourlyUsage.mapIndexed { hour, millis ->
                    val mins = millis / 60_000f
                    formatHourShort(hour) to mins
                }
            }
            BreakdownChartMode.DAILY_7_DAYS -> {
                val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
                sevenDayUsage.map { (date, millis) ->
                    val mins = millis / 60_000f
                    date.format(fmt) to mins
                }
            }
        }
    }

    var selectedPointIndex by remember(chartMode, points) {
        val maxIdx = points.indices.maxByOrNull { points[it].second } ?: -1
        mutableIntStateOf(maxIdx)
    }

    val animationProgress = remember(chartMode) { Animatable(0f) }
    LaunchedEffect(chartMode, points) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
        )
    }

    val textMeasurer = rememberTextMeasurer()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Usage Trend",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val selectedPoint = points.getOrNull(selectedPointIndex)
                    if (selectedPoint != null) {
                        Text(
                            text = "${selectedPoint.first}: ${formatMinutesValue(selectedPoint.second)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = chartMode == BreakdownChartMode.HOURLY_24H,
                        onClick = { onChartModeChange(BreakdownChartMode.HOURLY_24H) },
                        label = { Text("24h") },
                        modifier = Modifier.testTag("chart_mode_24h")
                    )
                    FilterChip(
                        selected = chartMode == BreakdownChartMode.DAILY_7_DAYS,
                        onClick = { onChartModeChange(BreakdownChartMode.DAILY_7_DAYS) },
                        label = { Text("7 Days") },
                        modifier = Modifier.testTag("chart_mode_7d")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(points) {
                                detectTapGestures { offset ->
                                    val leftPad = 42.dp.toPx()
                                    val rightPad = 14.dp.toPx()
                                    val chartWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                                    if (points.size > 1) {
                                        val fraction = ((offset.x - leftPad) / chartWidth).coerceIn(0f, 1f)
                                        selectedPointIndex = (fraction * (points.size - 1)).roundToInt()
                                    }
                                }
                            }
                    ) {
                        val leftPad = 42.dp.toPx()
                        val rightPad = 14.dp.toPx()
                        val topPad = 14.dp.toPx()
                        val bottomPad = 28.dp.toPx()

                        val chartWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                        val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)

                        val maxVal = (points.maxOfOrNull { it.second } ?: 0f).coerceAtLeast(5f)
                        val gridSteps = 4
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                        // Draw horizontal grid lines + Y-axis labels
                        for (i in 0..gridSteps) {
                            val ratio = i.toFloat() / gridSteps
                            val y = topPad + chartHeight * (1f - ratio)
                            drawLine(
                                color = gridColor,
                                start = Offset(leftPad, y),
                                end = Offset(leftPad + chartWidth, y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = dashEffect
                            )

                            val labelValue = (maxVal * ratio).roundToInt()
                            val labelText = if (labelValue >= 60) {
                                "${labelValue / 60}h${if (labelValue % 60 > 0) "${labelValue % 60}m" else ""}"
                            } else {
                                "${labelValue}m"
                            }
                            val textLayout = textMeasurer.measure(
                                text = labelText,
                                style = TextStyle(fontSize = 10.sp, color = onSurfaceVariantColor)
                            )
                            drawText(
                                textLayoutResult = textLayout,
                                topLeft = Offset(
                                    x = (leftPad - textLayout.size.width - 6.dp.toPx()).coerceAtLeast(0f),
                                    y = y - textLayout.size.height / 2f
                                )
                            )
                        }

                        if (points.isNotEmpty()) {
                            val stepX = if (points.size > 1) chartWidth / (points.size - 1) else 0f
                            val coords = points.mapIndexed { idx, pair ->
                                val x = leftPad + idx * stepX
                                val normalizedY = (pair.second / maxVal).coerceIn(0f, 1f) * animationProgress.value
                                val y = topPad + chartHeight * (1f - normalizedY)
                                Offset(x, y)
                            }

                            val linePath = Path()
                            val fillPath = Path()

                            coords.forEachIndexed { index, pt ->
                                if (index == 0) {
                                    linePath.moveTo(pt.x, pt.y)
                                    fillPath.moveTo(pt.x, topPad + chartHeight)
                                    fillPath.lineTo(pt.x, pt.y)
                                } else {
                                    val prev = coords[index - 1]
                                    val controlX = (prev.x + pt.x) / 2f
                                    linePath.cubicTo(controlX, prev.y, controlX, pt.y, pt.x, pt.y)
                                    fillPath.cubicTo(controlX, prev.y, controlX, pt.y, pt.x, pt.y)
                                }
                            }

                            fillPath.lineTo(coords.last().x, topPad + chartHeight)
                            fillPath.close()

                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        primaryColor.copy(alpha = 0.32f),
                                        primaryColor.copy(alpha = 0.02f)
                                    ),
                                    startY = topPad,
                                    endY = topPad + chartHeight
                                )
                            )

                            drawPath(
                                path = linePath,
                                color = primaryColor,
                                style = Stroke(
                                    width = 3.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )

                            // Draw X-axis labels and point markers
                            val labelModulo = if (points.size > 12) 4 else 1
                            coords.forEachIndexed { idx, pt ->
                                val isSelected = idx == selectedPointIndex
                                if (isSelected) {
                                    drawLine(
                                        color = primaryColor.copy(alpha = 0.5f),
                                        start = Offset(pt.x, topPad),
                                        end = Offset(pt.x, topPad + chartHeight),
                                        strokeWidth = 1.5.dp.toPx(),
                                        pathEffect = dashEffect
                                    )
                                    drawCircle(
                                        color = primaryColor,
                                        radius = 5.5.dp.toPx(),
                                        center = pt
                                    )
                                    drawCircle(
                                        color = Color.White,
                                        radius = 2.5.dp.toPx(),
                                        center = pt
                                    )
                                } else if (points.size <= 7 || points[idx].second > 0f) {
                                    drawCircle(
                                        color = primaryColor,
                                        radius = 3.dp.toPx(),
                                        center = pt
                                    )
                                }

                                if (idx % labelModulo == 0 || idx == points.lastIndex) {
                                    val xLabelLayout = textMeasurer.measure(
                                        text = points[idx].first,
                                        style = TextStyle(fontSize = 10.sp, color = onSurfaceVariantColor)
                                    )
                                    drawText(
                                        textLayoutResult = xLabelLayout,
                                        topLeft = Offset(
                                            x = (pt.x - xLabelLayout.size.width / 2f)
                                                .coerceIn(leftPad, size.width - xLabelLayout.size.width),
                                            y = topPad + chartHeight + 6.dp.toPx()
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeOfDayBreakdownCard(
    hourlyUsage: List<Long>,
    totalTime: Long
) {
    val morningMillis = hourlyUsage.slice(6..11).sum()
    val afternoonMillis = hourlyUsage.slice(12..17).sum()
    val eveningMillis = hourlyUsage.slice(18..23).sum()
    val nightMillis = hourlyUsage.slice(0..5).sum()
    val sumMillis = (morningMillis + afternoonMillis + eveningMillis + nightMillis).coerceAtLeast(1L)

    val periods = listOf(
        Triple("Morning (6 AM – 12 PM)", morningMillis, Color(0xFFFFB300)),
        Triple("Afternoon (12 PM – 6 PM)", afternoonMillis, Color(0xFF29B6F6)),
        Triple("Evening (6 PM – 12 AM)", eveningMillis, Color(0xFF7E57C2)),
        Triple("Late Night (12 AM – 6 AM)", nightMillis, Color(0xFFEF5350))
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Time of Day Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            periods.forEach { (label, millis, color) ->
                val fraction = if (totalTime > 0L && hourlyUsage.sum() > 0L) {
                    (millis.toFloat() / sumMillis.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatTime(millis),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .background(color)
                        )
                    }
                }
            }
        }
    }
}

private fun formatDetailedDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun formatMinutesValue(minutes: Float): String {
    val totalSeconds = (minutes * 60).roundToInt()
    val hrs = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return when {
        hrs > 0 -> "${hrs}h ${mins}m"
        mins > 0 -> "${mins}m"
        secs > 0 -> "${secs}s"
        else -> "0m"
    }
}

private fun formatHourLabel(hour: Int): String {
    val amPm = if (hour < 12) "AM" else "PM"
    val h = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$h:00 $amPm"
}

private fun formatHourShort(hour: Int): String {
    val amPm = if (hour < 12) "a" else "p"
    val h = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$h$amPm"
}

private fun getPeriodOfDayLabel(hour: Int): String {
    return when (hour) {
        in 6..11 -> "Morning Session"
        in 12..17 -> "Afternoon Session"
        in 18..23 -> "Evening Session"
        else -> "Late Night Session"
    }
}

private fun openSystemAppInfo(context: Context, packageName: String) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
