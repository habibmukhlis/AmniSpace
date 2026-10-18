package neth.iecal.questphone.core.utils

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import nethical.questphone.data.ScreentimeStat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageStatsHelper(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val guardian = UnmatchedCloseEventGuardian()
    fun getForegroundStatsByTimestamps(start: Long, end: Long): List<ScreentimeStat> {
        var adjustedStart = start
        // List to store currently running foreground processes
        val foregroundProcesses = mutableListOf<String>()
        if (end >= System.currentTimeMillis() - 1500) {
            // Get currently running foreground processes
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appProcesses = activityManager.runningAppProcesses
            for (appProcess in appProcesses) {
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    foregroundProcesses.add(appProcess.processName)
                }
            }
        }

        // Query usage events from the UsageStatsManager
        val events = usageStatsManager.queryEvents(start, end)
        // Map to track when apps move to the foreground (nullable Long to handle null values)
        val moveToForegroundMap = mutableMapOf<AppClass, Long?>()
        // List to store foreground stats for each app
        val componentForegroundStats = mutableListOf<ComponentForegroundStat>()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            // Skip if className is null
            val className = event.className ?: continue

            val appClass = AppClass(event.packageName, className)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, 4 -> {
                    // App moved to the foreground: store the timestamp
                    moveToForegroundMap[appClass] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED, 3 -> {
                    // App moved to the background: calculate usage duration
                    var eventBeginTime: Long? = moveToForegroundMap[appClass]
                    if (eventBeginTime != null) {
                        // If there's a start time, set it to null (app is no longer in the foreground)
                        moveToForegroundMap[appClass] = null
                    } else if (moveToForegroundMap.keys.none { event.packageName == it.packageName } &&
                        guardian.test(event, adjustedStart)) {
                        // If no start time exists and the guardian confirms it's a valid unmatched close event, use the start time
                        eventBeginTime = adjustedStart
                    } else {
                        // Skip if it's a faulty unmatched close event
                        continue
                    }

                    // Calculate the end time, handling null values
                    val endTime = moveToForegroundMap.entries
                        .filter { event.packageName == it.key.packageName }
                        .filter { it.value != null }.minOfOrNull { it.value!! } ?: event.timeStamp

                    // Add the foreground stat
                    componentForegroundStats.add(ComponentForegroundStat(eventBeginTime, endTime, event.packageName))
                }
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    // Handle device shutdown: treat all open events as closed
                    for (key in moveToForegroundMap.keys) {
                        val startTime = moveToForegroundMap[key]
                        if (startTime == null) continue // Skip if no start time exists

                        // Add the foreground stat for the shutdown event
                        componentForegroundStats.add(ComponentForegroundStat(startTime, event.timeStamp, key.packageName))

                        // Set all components of the app to null (no longer in the foreground)
                        moveToForegroundMap.keys.filter { key.packageName == it.packageName }.forEach { moveToForegroundMap[it] = null }
                    }
                }
                UsageEvents.Event.DEVICE_STARTUP -> {
                    // Handle device startup: clear all open events
                    for (key in moveToForegroundMap.keys) {
                        moveToForegroundMap[key] = null
                    }
                    // Update the start time to the startup event's timestamp
                    adjustedStart = adjustedStart.coerceAtLeast(event.timeStamp)
                }
            }
        }

        // Handle remaining open events (apps still in the foreground)
        for (key in moveToForegroundMap.keys) {
            val startTime = moveToForegroundMap[key]
            if (startTime == null) continue // Skip if no start time exists

            // Check if the app is still in the foreground
            for (foregroundProcess in foregroundProcesses) {
                if (foregroundProcess.contains(key.packageName)) {
                    // Add the foreground stat for the remaining open event
                    componentForegroundStats.add(ComponentForegroundStat(startTime, minOf(System.currentTimeMillis(), end), key.packageName))
                    break
                }
            }
        }

        // If no events were found but there are foreground processes, assume they were used the entire period
        if (moveToForegroundMap.isEmpty()) {
            val packageManager = context.packageManager
            for (foregroundProcess in foregroundProcesses) {
                if (packageManager.getLaunchIntentForPackage(foregroundProcess) != null) {
                    componentForegroundStats.add(ComponentForegroundStat(adjustedStart, minOf(System.currentTimeMillis(), end), foregroundProcess))
                    Log.d("UsageStatsHelper", "Assuming that application $foregroundProcess has been used the whole query time")
                }
            }
        }

        // Aggregate the foreground stats into usage stats
        return aggregateForegroundStats(componentForegroundStats)
    }
    fun getForegroundStatsByRelativeDay(offset: Int): List<ScreentimeStat> {
        val queryDay = LocalDate.now().minusDays(offset.toLong())
        val start = queryDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = queryDay.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return getForegroundStatsByTimestamps(start, end)
    }

    fun getForegroundStatsByDay(queryDate: LocalDate): List<ScreentimeStat> {
        val start = queryDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = queryDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return getForegroundStatsByTimestamps(start, end)
    }

    private fun aggregateForegroundStats(foregroundStats: List<ComponentForegroundStat>): List<ScreentimeStat> {
        val usageStats = mutableListOf<ScreentimeStat>()
        if (foregroundStats.isEmpty()) return usageStats

        // Map to store total foreground time for each app
        val applicationTotalForegroundTime = mutableMapOf<String, Long>()
        // Map to store start times for each app
        val applicationStartTimes = mutableMapOf<String, MutableList<ZonedDateTime>>()

        for (foregroundStat in foregroundStats) {
            // Calculate total foreground time for each app
            applicationTotalForegroundTime[foregroundStat.packageName] =
                applicationTotalForegroundTime.getOrDefault(foregroundStat.packageName, 0) +
                        (foregroundStat.endTime - foregroundStat.beginTime)

            // Collect start times for each app
            val startTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(foregroundStat.beginTime),
                ZoneId.systemDefault()
            )
            applicationStartTimes.getOrPut(foregroundStat.packageName) { mutableListOf() }.add(startTime)
        }

        // Create Stat objects with total time and start times
        for ((packageName, totalTime) in applicationTotalForegroundTime) {
            val startTimes = applicationStartTimes[packageName] ?: listOf()
            usageStats.add(ScreentimeStat(packageName, totalTime, startTimes))
        }

        // Sort by total time in descending order
        return usageStats.sortedByDescending { it.totalTime }
    }

    private class AppClass(val packageName: String, val className: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AppClass
            if (packageName != other.packageName) return false
            if (className != other.className) return false
            return true
        }

        override fun hashCode(): Int {
            var result = packageName.hashCode()
            result = 31 * result + className.hashCode()
            return result
        }
    }

    private class ComponentForegroundStat(val beginTime: Long, val endTime: Long, val packageName: String)

    private class UnmatchedCloseEventGuardian {
        fun test(event: UsageEvents.Event, start: Long): Boolean {
            // Implement logic to test for unmatched close events
            return true
        }
    }

    fun getAppSessionsForDay(packageName: String, queryDate: LocalDate): List<AppUsageSession> {
        val zone = ZoneId.systemDefault()
        val start = queryDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = queryDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return extractComponentForegroundStats(start, end)
            .filter { it.packageName == packageName && it.endTime > it.beginTime }
            .map {
                val clampedStart = it.beginTime.coerceIn(start, end)
                val clampedEnd = it.endTime.coerceIn(start, end)
                AppUsageSession(
                    startTimeMillis = clampedStart,
                    endTimeMillis = clampedEnd,
                    durationMillis = (clampedEnd - clampedStart).coerceAtLeast(0L)
                )
            }
            .filter { it.durationMillis > 0L }
            .sortedByDescending { it.startTimeMillis }
    }

    fun getAppHourlyUsageForDay(packageName: String, queryDate: LocalDate): List<Long> {
        val zone = ZoneId.systemDefault()
        val dayStartZoned = queryDate.atStartOfDay(zone)
        val start = dayStartZoned.toInstant().toEpochMilli()
        val end = queryDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val hourly = LongArray(24)

        val intervals = extractComponentForegroundStats(start, end)
            .filter { it.packageName == packageName && it.endTime > it.beginTime }

        for (interval in intervals) {
            for (hour in 0..23) {
                val hourStart = dayStartZoned.plusHours(hour.toLong()).toInstant().toEpochMilli()
                val hourEnd = dayStartZoned.plusHours((hour + 1).toLong()).toInstant().toEpochMilli()
                val overlapStart = maxOf(interval.beginTime, hourStart)
                val overlapEnd = minOf(interval.endTime, hourEnd)
                if (overlapEnd > overlapStart) {
                    hourly[hour] += (overlapEnd - overlapStart)
                }
            }
        }
        return hourly.toList()
    }

    fun getAppDailyUsageForLast7Days(
        packageName: String,
        anchorDate: LocalDate = LocalDate.now()
    ): List<Pair<LocalDate, Long>> {
        val result = mutableListOf<Pair<LocalDate, Long>>()
        for (offset in 6 downTo 0) {
            val day = anchorDate.minusDays(offset.toLong())
            val dayStat = getForegroundStatsByDay(day).firstOrNull { it.packageName == packageName }
            result.add(day to (dayStat?.totalTime ?: 0L))
        }
        return result
    }

    private fun extractComponentForegroundStats(start: Long, end: Long): List<ComponentForegroundStat> {
        var adjustedStart = start
        val foregroundProcesses = mutableListOf<String>()
        if (end >= System.currentTimeMillis() - 1500) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appProcesses = activityManager.runningAppProcesses ?: emptyList()
            for (appProcess in appProcesses) {
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                ) {
                    foregroundProcesses.add(appProcess.processName)
                }
            }
        }

        val events = usageStatsManager.queryEvents(start, end)
        val moveToForegroundMap = mutableMapOf<AppClass, Long?>()
        val componentForegroundStats = mutableListOf<ComponentForegroundStat>()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val className = event.className ?: continue
            val appClass = AppClass(event.packageName, className)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, 4 -> {
                    moveToForegroundMap[appClass] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED, 3 -> {
                    var eventBeginTime: Long? = moveToForegroundMap[appClass]
                    if (eventBeginTime != null) {
                        moveToForegroundMap[appClass] = null
                    } else if (moveToForegroundMap.keys.none { event.packageName == it.packageName } &&
                        guardian.test(event, adjustedStart)
                    ) {
                        eventBeginTime = adjustedStart
                    } else {
                        continue
                    }

                    val endTime = moveToForegroundMap.entries
                        .filter { event.packageName == it.key.packageName }
                        .filter { it.value != null }.minOfOrNull { it.value!! } ?: event.timeStamp

                    componentForegroundStats.add(
                        ComponentForegroundStat(eventBeginTime, endTime, event.packageName)
                    )
                }
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    for (key in moveToForegroundMap.keys) {
                        val startTime = moveToForegroundMap[key] ?: continue
                        componentForegroundStats.add(
                            ComponentForegroundStat(startTime, event.timeStamp, key.packageName)
                        )
                        moveToForegroundMap.keys.filter { key.packageName == it.packageName }
                            .forEach { moveToForegroundMap[it] = null }
                    }
                }
                UsageEvents.Event.DEVICE_STARTUP -> {
                    for (key in moveToForegroundMap.keys) {
                        moveToForegroundMap[key] = null
                    }
                    adjustedStart = adjustedStart.coerceAtLeast(event.timeStamp)
                }
            }
        }

        for (key in moveToForegroundMap.keys) {
            val startTime = moveToForegroundMap[key] ?: continue
            for (foregroundProcess in foregroundProcesses) {
                if (foregroundProcess.contains(key.packageName)) {
                    componentForegroundStats.add(
                        ComponentForegroundStat(
                            startTime,
                            minOf(System.currentTimeMillis(), end),
                            key.packageName
                        )
                    )
                    break
                }
            }
        }

        if (moveToForegroundMap.isEmpty()) {
            val packageManager = context.packageManager
            for (foregroundProcess in foregroundProcesses) {
                if (packageManager.getLaunchIntentForPackage(foregroundProcess) != null) {
                    componentForegroundStats.add(
                        ComponentForegroundStat(
                            adjustedStart,
                            minOf(System.currentTimeMillis(), end),
                            foregroundProcess
                        )
                    )
                }
            }
        }

        return componentForegroundStats
    }

    fun getDefaultLauncherPackageName(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }
}

data class AppUsageSession(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationMillis: Long
)