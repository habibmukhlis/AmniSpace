package neth.iecal.questphone.app.screens.etc

import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.questphone.R
import neth.iecal.questphone.app.screens.quest.setup.deep_focus.SelectAppsDialog
import neth.iecal.questphone.backed.repositories.UserRepositoryEntryPoint
import neth.iecal.questphone.core.services.AppBlockerService
import neth.iecal.questphone.core.services.AppBlockerServiceInfo
import neth.iecal.questphone.core.services.INTENT_ACTION_REFRESH_APP_BLOCKER
import neth.iecal.questphone.core.utils.UsageStatsHelper
import nethical.questphone.core.core.utils.managers.sendRefreshRequest
import nethical.questphone.data.ScreentimeStat
import nethical.questphone.data.game.InventoryItem
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreentimeStatsScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val userRepository = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            UserRepositoryEntryPoint::class.java
        ).userRepository()
    }

    val userCoins by userRepository.coinsState.collectAsState()
    val adderPrice by userRepository.distractionAdderPriceState.collectAsState()
    val removerPrice by userRepository.distractionRemoverPriceState.collectAsState()
    val inventoryMap by userRepository.inventoryState.collectAsState()
    val adderOwned = inventoryMap[InventoryItem.DISTRACTION_ADDER] ?: 0
    val removerOwned = inventoryMap[InventoryItem.DISTRACTION_REMOVER] ?: 0

    var usageStats by remember { mutableStateOf<List<ScreentimeStat>>(emptyList()) }
    var totalUsage by remember { mutableStateOf("0m") }
    var selectedDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var currentDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var earliestDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showMenu by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showDistractingAppsDialog by remember { mutableStateOf(false) }
    var showIgnoredAppsDialog by remember { mutableStateOf(false) }
    var showToolPricingDialog by remember { mutableStateOf(false) }
    var pendingDistractionAction by remember { mutableStateOf<PendingDistractionAction?>(null) }
    var ignoredPackages by remember { mutableStateOf(setOf<String>()) }
    var blockedPackages by remember { mutableStateOf(userRepository.getBlockedPackages()) }
    var isLoading by remember { mutableStateOf(true) }

    // Currently selected app for detailed Per-App Usage Breakdown
    var selectedAppStat by remember { mutableStateOf<ScreentimeStat?>(null) }

    val prefs = remember { context.getSharedPreferences("digipaws_prefs", Context.MODE_PRIVATE) }
    val distractionsPrefs = remember { context.getSharedPreferences("distractions", Context.MODE_PRIVATE) }

    fun syncBlockedApps(updated: Set<String>) {
        blockedPackages = updated
        userRepository.updateBlockedAppsSet(updated)
        distractionsPrefs.edit { putStringSet("distracting_apps", updated) }
        sendRefreshRequest(context, INTENT_ACTION_REFRESH_APP_BLOCKER)
        AppBlockerServiceInfo.appBlockerService?.loadLockedApps()
        if (!AppBlockerServiceInfo.isUsingAccessibilityService && AppBlockerServiceInfo.appBlockerService == null && updated.isNotEmpty()) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AppBlockerService::class.java))
            } catch (e: Exception) {
                Log.e("ScreentimeStatsScreen", "Failed to start AppBlockerService: ${e.message}")
            }
        }
    }

    fun applyImmediateToggle(packageName: String) {
        val updated = blockedPackages.toMutableSet().apply {
            if (contains(packageName)) remove(packageName) else add(packageName)
        }
        syncBlockedApps(updated)
    }

    fun requestToggleBlockedPackage(packageName: String, appLabel: String = packageName) {
        val isCurrentlyBlocked = blockedPackages.contains(packageName)
        if (!isCurrentlyBlocked) {
            // Adding to Distracting Apps / Blocklist
            if (adderPrice <= 0) {
                applyImmediateToggle(packageName)
                Toast.makeText(
                    context,
                    "Added $appLabel to Distracting Apps (Free)",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                pendingDistractionAction = PendingDistractionAction.SingleToggle(
                    packageName = packageName,
                    appLabel = appLabel,
                    isAdding = true
                )
            }
        } else {
            // Removing from Distracting Apps / Blocklist always requires Distraction Remover (20..500)
            pendingDistractionAction = PendingDistractionAction.SingleToggle(
                packageName = packageName,
                appLabel = appLabel,
                isAdding = false
            )
        }
    }

    fun loadUsageStats(date: LocalDate = LocalDate.now()) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            val usageStatsHelper = UsageStatsHelper(context)
            val launcherPkg = getDefaultLauncherPackage(context.packageManager)
            val list = usageStatsHelper.getForegroundStatsByDay(date).filter {
                it.totalTime >= 1_000L &&
                    it.packageName != launcherPkg &&
                    it.packageName !in ignoredPackages
            }
            val total = list.sumOf { it.totalTime }

            withContext(Dispatchers.Main) {
                usageStats = list
                totalUsage = formatTime(total)
                isLoading = false
            }
        }
    }

    fun toggleIgnoredPackage(packageName: String) {
        val updated = ignoredPackages.toMutableSet().apply {
            if (contains(packageName)) remove(packageName) else add(packageName)
        }
        ignoredPackages = updated
        saveIgnoredPackages(prefs, updated)
        val localDate = Instant.ofEpochMilli(selectedDate)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        loadUsageStats(localDate)
    }

    fun findDataRange() {
        scope.launch(Dispatchers.IO) {
            val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                0,
                System.currentTimeMillis()
            )
            earliestDate = stats.minOfOrNull { it.firstTimeStamp } ?: System.currentTimeMillis()
            currentDate = System.currentTimeMillis()
            selectedDate = currentDate.coerceAtLeast(earliestDate)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasUsageStatsPermission(context)) {
            showPermissionDialog = true
        }

        withContext(Dispatchers.IO) {
            ignoredPackages = loadIgnoredPackages(prefs)
            blockedPackages = userRepository.getBlockedPackages()
        }

        loadUsageStats()
        findDataRange()
    }

    // Render gated tool modal above both Screentime overview and Per-App Usage Breakdown
    pendingDistractionAction?.let { action ->
        DistractionToolGateDialog(
            action = action,
            userRepository = userRepository,
            userCoins = userCoins,
            adderPrice = adderPrice,
            removerPrice = removerPrice,
            adderOwned = adderOwned,
            removerOwned = removerOwned,
            onDismiss = { pendingDistractionAction = null },
            onActionApproved = { feedbackMessage ->
                when (action) {
                    is PendingDistractionAction.SingleToggle -> {
                        applyImmediateToggle(action.packageName)
                    }
                    is PendingDistractionAction.BulkUpdate -> {
                        syncBlockedApps(action.targetBlockedSet)
                    }
                }
                pendingDistractionAction = null
                Toast.makeText(context, feedbackMessage, Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showToolPricingDialog) {
        DistractionToolPricingSettingsDialog(
            userRepository = userRepository,
            currentAdderPrice = adderPrice,
            currentRemoverPrice = removerPrice,
            userCoins = userCoins,
            removerOwned = removerOwned,
            onDismiss = { showToolPricingDialog = false }
        )
    }

    // If an app is selected, display the Per-App Usage Breakdown screen
    val activeAppStat = selectedAppStat
    if (activeAppStat != null) {
        AppUsageBreakdownScreen(
            stat = activeAppStat,
            selectedDateMillis = selectedDate,
            isBlocked = blockedPackages.contains(activeAppStat.packageName),
            isIgnored = ignoredPackages.contains(activeAppStat.packageName),
            adderPrice = adderPrice,
            removerPrice = removerPrice,
            adderOwned = adderOwned,
            removerOwned = removerOwned,
            onToggleBlocked = { pkg, label -> requestToggleBlockedPackage(pkg, label) },
            onToggleIgnored = { pkg -> toggleIgnoredPackage(pkg) },
            onBack = { selectedAppStat = null }
        )
        return
    }

    if (showPermissionDialog) {
        PermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onAccept = {
                requestUsageStatsPermission(context)
                showPermissionDialog = false
            }
        )
    }

    if (showDistractingAppsDialog) {
        val tempSelected = remember(blockedPackages) {
            mutableStateListOf<String>().apply { addAll(blockedPackages) }
        }
        SelectAppsDialog(
            selectedApps = tempSelected,
            onDismiss = { showDistractingAppsDialog = false },
            onConfirm = {
                val targetSet = tempSelected.toSet()
                val addedApps = targetSet - blockedPackages
                val removedApps = blockedPackages - targetSet
                showDistractingAppsDialog = false

                if (addedApps.isEmpty() && removedApps.isEmpty()) {
                    return@SelectAppsDialog
                }

                val requiresGate = removedApps.isNotEmpty() || (addedApps.isNotEmpty() && adderPrice > 0)
                if (!requiresGate) {
                    syncBlockedApps(targetSet)
                    Toast.makeText(
                        context,
                        "Added ${addedApps.size} app(s) to Distracting Apps (Free)",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    pendingDistractionAction = PendingDistractionAction.BulkUpdate(
                        targetBlockedSet = targetSet,
                        addedCount = addedApps.size,
                        removedCount = removedApps.size
                    )
                }
            }
        )
    }

    if (showIgnoredAppsDialog) {
        val tempIgnored = remember(ignoredPackages) {
            mutableStateListOf<String>().apply { addAll(ignoredPackages) }
        }
        SelectAppsDialog(
            selectedApps = tempIgnored,
            onDismiss = { showIgnoredAppsDialog = false },
            onConfirm = {
                ignoredPackages = tempIgnored.toSet()
                saveIgnoredPackages(prefs, ignoredPackages)
                val localDate = Instant.ofEpochMilli(selectedDate)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                loadUsageStats(localDate)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Usage & Blocklist", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (navController != null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDistractingAppsDialog = true },
                        modifier = Modifier.testTag("manage_distracting_apps_top_button")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.outline_lock_24),
                            contentDescription = "Manage Distracting Apps"
                        )
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Menu")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Manage Distracting Apps") },
                            onClick = {
                                showMenu = false
                                showDistractingAppsDialog = true
                            },
                            leadingIcon = {
                                Icon(painter = painterResource(R.drawable.outline_lock_24), null)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text("Tool Pricing (Add: $adderPrice • Remove: $removerPrice)")
                            },
                            onClick = {
                                showMenu = false
                                showToolPricingDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Ignored Apps") },
                            onClick = {
                                showMenu = false
                                showIgnoredAppsDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, null)
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val pm = context.packageManager

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        TotalUsageCard(
                            totalUsage = totalUsage,
                            selectedDate = selectedDate,
                            onDateClick = {
                                showDatePicker(context, selectedDate, earliestDate, currentDate) { newDate ->
                                    selectedDate = newDate
                                    val localDate = Instant.ofEpochMilli(newDate)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    loadUsageStats(localDate)
                                }
                            }
                        )
                    }

                    // Distracting Apps / Blocklist & Commitment Pricing Banner Card
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDistractingAppsDialog = true }
                                .testTag("manage_blocklist_banner_card"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Distracting Apps Blocklist (${blockedPackages.size} blocked)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = buildString {
                                                append("Add cost: ")
                                                append(if (adderPrice == 0) "Free (0)" else "$adderPrice coins")
                                                append(" • Remove cost: $removerPrice coins")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = { showDistractingAppsDialog = true },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Edit Blocklist", style = MaterialTheme.typography.labelMedium)
                                    }
                                    OutlinedButton(
                                        onClick = { showToolPricingDialog = true },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("open_tool_pricing_settings_button"),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Tool Prices", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }

                    if (usageStats.isEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No usage data available for this date",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        // Top 3 Most Used Apps Section (each card is selectable/clickable)
                        item {
                            TopAppsSection(
                                usageStats = usageStats.take(3),
                                blockedPackages = blockedPackages,
                                onAppClick = { stat -> selectedAppStat = stat },
                                onToggleBlock = { pkg, appLabel ->
                                    requestToggleBlockedPackage(pkg, appLabel)
                                }
                            )
                        }

                        item {
                            Text(
                                text = "All Used Apps Today",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        items(usageStats, key = { it.packageName }) { stat ->
                            val appInfo = remember(stat.packageName) {
                                try {
                                    pm.getApplicationInfo(stat.packageName, 0)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            val appLabel = remember(stat.packageName) {
                                appInfo?.loadLabel(pm)?.toString() ?: stat.packageName
                            }
                            val iconBitmap = remember(stat.packageName) {
                                try {
                                    appInfo?.loadIcon(pm)?.toBitmap()?.asImageBitmap()
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            val isBlocked = blockedPackages.contains(stat.packageName)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedAppStat = stat }
                                    .testTag("usage_app_card_${stat.packageName}"),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (iconBitmap != null) {
                                        Image(
                                            bitmap = iconBitmap,
                                            contentDescription = appLabel,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = appLabel.take(1).uppercase(),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = appLabel,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(2.dp))

                                        Text(
                                            text = "${formatTime(stat.totalTime)} • ${maxOf(1, stat.startTimes.size)} opens",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    // Synchronized Block / Unblock Button right on the card
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (isBlocked) {
                                            MaterialTheme.colorScheme.errorContainer
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                requestToggleBlockedPackage(stat.packageName, appLabel)
                                            }
                                            .testTag("quick_block_${stat.packageName}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (isBlocked) Icons.Default.Check else Icons.Default.Add,
                                                contentDescription = if (isBlocked) "Blocked" else "Add to Blocklist",
                                                modifier = Modifier.size(14.dp),
                                                tint = if (isBlocked) {
                                                    MaterialTheme.colorScheme.onErrorContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isBlocked) {
                                                    "Blocked"
                                                } else if (adderPrice == 0) {
                                                    "Block"
                                                } else {
                                                    "Block ($adderPrice)"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isBlocked) {
                                                    MaterialTheme.colorScheme.onErrorContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "View Usage Breakdown",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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
fun TotalUsageCard(
    totalUsage: String,
    selectedDate: Long,
    onDateClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Total Screen Time",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                totalUsage,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onDateClick,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    Icons.Default.DateRange,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(formatDate(selectedDate))
            }
        }
    }
}

@Composable
fun TopAppsSection(
    usageStats: List<ScreentimeStat>,
    blockedPackages: Set<String> = emptySet(),
    onAppClick: (ScreentimeStat) -> Unit = {},
    onToggleBlock: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val pm = context.packageManager

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Most Used Today",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Tap card for breakdown",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val maxTime = (usageStats.maxOfOrNull { it.totalTime } ?: 1L).coerceAtLeast(1L)

            usageStats.forEachIndexed { index, stat ->
                val appInfo = remember(stat.packageName) {
                    try {
                        pm.getApplicationInfo(stat.packageName, 0)
                    } catch (_: Exception) {
                        null
                    }
                }
                val appLabel = remember(stat.packageName) {
                    appInfo?.loadLabel(pm)?.toString() ?: stat.packageName
                }
                val iconBitmap = remember(stat.packageName) {
                    try {
                        appInfo?.loadIcon(pm)?.toBitmap()?.asImageBitmap()
                    } catch (_: Exception) {
                        null
                    }
                }
                val percentage = (stat.totalTime.toFloat() / maxTime.toFloat()).coerceIn(0f, 1f)
                val isBlocked = blockedPackages.contains(stat.packageName)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onAppClick(stat) }
                        .testTag("most_used_card_${stat.packageName}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(
                                    when (index) {
                                        0 -> Color(0xFFFFD700)
                                        1 -> Color(0xFFC0C0C0)
                                        else -> Color(0xFFCD7F32)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${index + 1}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = appLabel,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = appLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = formatTime(stat.totalTime),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(percentage)
                                        .background(
                                            when (index) {
                                                0 -> Color(0xFF2196F3)
                                                1 -> Color(0xFFF44336)
                                                else -> Color(0xFF4CAF50)
                                            }
                                        )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { onToggleBlock(stat.packageName, appLabel) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (isBlocked) Icons.Default.Lock else Icons.Default.Add,
                                contentDescription = if (isBlocked) "Blocked" else "Add to Blocklist",
                                tint = if (isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
        title = { Text("Enable Device Usage Access") },
        text = {
            Text(
                "QuestPhone requires device usage access to monitor apps, helping you manage screen time effectively. All data stays securely on your device."
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun formatTime(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

fun formatDate(millis: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(millis)
}

fun loadIgnoredPackages(prefs: SharedPreferences): Set<String> {
    return prefs.getStringSet("ignored_packages", emptySet()) ?: emptySet()
}

fun saveIgnoredPackages(prefs: SharedPreferences, packages: Set<String>) {
    prefs.edit().putStringSet("ignored_packages", packages).apply()
}

fun getDefaultLauncherPackage(pm: PackageManager): String? {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
    }
    val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolveInfo?.activityInfo?.packageName
}

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOpsManager.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun requestUsageStatsPermission(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(intent)
}

fun showDatePicker(
    context: Context,
    selectedDate: Long,
    startDate: Long,
    endDate: Long,
    onDateSelected: (Long) -> Unit
) {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = selectedDate

    val datePicker = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val pickedCalendar = Calendar.getInstance()
            pickedCalendar.set(year, month, dayOfMonth)
            onDateSelected(pickedCalendar.timeInMillis)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    datePicker.datePicker.minDate = startDate
    datePicker.datePicker.maxDate = endDate
    datePicker.show()
}
