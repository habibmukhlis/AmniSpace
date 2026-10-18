package neth.iecal.questphone.app.screens.onboard.subscreens

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import neth.iecal.questphone.R
import neth.iecal.questphone.backed.repositories.UserRepository
import neth.iecal.questphone.core.services.AppBlockerServiceInfo
import neth.iecal.questphone.core.services.INTENT_ACTION_REFRESH_APP_BLOCKER
import nethical.questphone.core.core.utils.managers.reloadApps
import nethical.questphone.core.core.utils.managers.sendRefreshRequest
import nethical.questphone.data.AppInfo
import nethical.questphone.data.game.InventoryItem
import javax.inject.Inject
import kotlin.math.roundToInt

enum class SelectAppsModes {
    ALLOW_ADD, // allow adding apps (used when Distraction Adder is consumed from Inventory)
    ALLOW_REMOVE, // only allow removing one app, block adding any app
    ALLOW_ADD_AND_REMOVE // no restrictions (Onboarding setup)
}

@HiltViewModel
class SelectAppsViewModel @Inject constructor(
    application: Application,
    private val userRepository: UserRepository
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext

    val searchQuery = mutableStateOf("")

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _selectedApps = MutableStateFlow<Set<String>>(emptySet())
    val selectedApps: StateFlow<Set<String>> = _selectedApps

    val distractionAdderPrice: StateFlow<Int> = userRepository.distractionAdderPriceState
    val distractionRemoverPrice: StateFlow<Int> = userRepository.distractionRemoverPriceState

    init {
        viewModelScope.launch {
            // Ensure defaults (0 for Adder, 50 for Remover) are initialized and persisted if skipped
            val currentAdder = userRepository.getDistractionAdderPrice()
            val currentRemover = userRepository.getDistractionRemoverPrice()
            userRepository.updateDistractionToolPrices(currentAdder, currentRemover)
            loadApps()
            loadSelectedApps()
        }
    }

    fun updateAdderPrice(price: Int) {
        val coerced = price.coerceIn(0, 10)
        userRepository.updateDistractionToolPrices(
            adderPrice = coerced,
            removerPrice = userRepository.getDistractionRemoverPrice()
        )
    }

    fun updateRemoverPrice(price: Int) {
        val coerced = price.coerceIn(20, 500)
        userRepository.updateDistractionToolPrices(
            adderPrice = userRepository.getDistractionAdderPrice(),
            removerPrice = coerced
        )
    }

    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            reloadApps(context.packageManager, context)
                .onSuccess {
                    _apps.value = it
                }
                .onFailure {
                    Log.e("SelectAppsVM", "Failed to load apps: $it")
                    Toast.makeText(context, "Error loading apps", Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun loadSelectedApps() {
        _selectedApps.value = userRepository.getBlockedPackages()
    }

    fun toggleApp(packageName: String) {
        val updated = _selectedApps.value.toMutableSet().apply {
            if (contains(packageName)) remove(packageName) else add(packageName)
        }
        _selectedApps.value = updated
        saveToPrefs()
    }

    private fun saveToPrefs() {
        userRepository.updateBlockedAppsSet(selectedApps.value)
        Log.d("Saving distracting apps list", selectedApps.toString())
        sendRefreshRequest(context, INTENT_ACTION_REFRESH_APP_BLOCKER)
        AppBlockerServiceInfo.appBlockerService?.loadLockedApps()
    }

    fun clearSelectedApps() {
        _selectedApps.value = emptySet()
        saveToPrefs()
    }

    fun addApp(packageName: String) {
        if (!_selectedApps.value.contains(packageName)) {
            val updated = _selectedApps.value.toMutableSet().apply { add(packageName) }
            _selectedApps.value = updated
            saveToPrefs()
        }
    }

    fun removeApp(packageName: String) {
        if (_selectedApps.value.contains(packageName)) {
            val updated = _selectedApps.value.toMutableSet().apply { remove(packageName) }
            _selectedApps.value = updated
            saveToPrefs()
        }
    }
}

@Composable
fun SelectApps(
    selectAppsModes: SelectAppsModes = SelectAppsModes.ALLOW_ADD_AND_REMOVE,
    viewModel: SelectAppsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val apps by viewModel.apps.collectAsState()
    val selectedApps by viewModel.selectedApps.collectAsState()
    val adderPrice by viewModel.distractionAdderPrice.collectAsState()
    val removerPrice by viewModel.distractionRemoverPrice.collectAsState()

    var searchQuery by viewModel.searchQuery
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }
    // Track apps added during this session in ALLOW_ADD mode so user can add as many apps as they want and undo if needed
    var addedInSession by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Track single app removed in ALLOW_REMOVE mode
    var specialChosenApp by remember { mutableStateOf<String?>(null) }

    val sp = context.getSharedPreferences("distractions", Context.MODE_PRIVATE)

    fun saveToBlocker() {
        sp.edit { putStringSet("distracting_apps", selectedApps.toSet()) }
        sendRefreshRequest(context, INTENT_ACTION_REFRESH_APP_BLOCKER)
        AppBlockerServiceInfo.appBlockerService?.loadLockedApps()
        if (!AppBlockerServiceInfo.isUsingAccessibilityService && AppBlockerServiceInfo.appBlockerService == null) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    android.content.Intent(context, neth.iecal.questphone.core.services.AppBlockerService::class.java)
                )
            } catch (e: Exception) {
                Log.e("SelectApps", "Failed to start AppBlockerService: ${e.message}")
            }
        }
    }

    fun handleAppSelection(packageName: String) {
        when (selectAppsModes) {
            SelectAppsModes.ALLOW_ADD -> {
                // Allow adding any number of apps without restriction, and undoing apps added in this session
                if (!selectedApps.contains(packageName)) {
                    addedInSession = addedInSession + packageName
                    viewModel.addApp(packageName)
                } else if (addedInSession.contains(packageName)) {
                    addedInSession = addedInSession - packageName
                    viewModel.removeApp(packageName)
                }
            }

            SelectAppsModes.ALLOW_REMOVE -> {
                // Only allow removing one app, but allow undoing that removal
                if (specialChosenApp == null && selectedApps.contains(packageName)) {
                    // Allow removing one app
                    viewModel.removeApp(packageName)
                    specialChosenApp = packageName
                } else if (specialChosenApp == packageName) {
                    // Allow undoing the removal (re-adding the special app that was removed)
                    viewModel.addApp(packageName)
                    specialChosenApp = null
                }
                // Block adding new apps or removing more apps
            }

            SelectAppsModes.ALLOW_ADD_AND_REMOVE -> {
                // No restrictions - toggle selection
                if (selectedApps.contains(packageName)) {
                    viewModel.removeApp(packageName)
                } else {
                    viewModel.addApp(packageName)
                }
            }
        }
        saveToBlocker()
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(36.dp))

        Text(
            text = when (selectAppsModes) {
                SelectAppsModes.ALLOW_ADD -> "Distraction Adder"
                SelectAppsModes.ALLOW_REMOVE -> "Distraction Remover"
                SelectAppsModes.ALLOW_ADD_AND_REMOVE -> "Select Distractions & Commitment"
            },
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = when (selectAppsModes) {
                SelectAppsModes.ALLOW_ADD -> "Select apps to add to your distraction blocklist."
                SelectAppsModes.ALLOW_REMOVE -> "Uncheck 1 blocked app to remove it from your distraction list."
                SelectAppsModes.ALLOW_ADD_AND_REMOVE -> "Choose distracting apps and set your tool prices for adding or removing apps later."
            },
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectAppsModes == SelectAppsModes.ALLOW_ADD_AND_REMOVE) {
                item {
                    DistractionToolPricingSetupCard(
                        adderPrice = adderPrice,
                        removerPrice = removerPrice,
                        onAdderPriceChange = { viewModel.updateAdderPrice(it) },
                        onRemoverPriceChange = { viewModel.updateRemoverPrice(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search Apps (${selectedApps.size} blocked)") },
                    placeholder = { Text("Type app name...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    singleLine = true
                )
            }

            items(filteredApps, key = { it.packageName }) { (appName, packageName) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            handleAppSelection(packageName)
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isSelected = selectedApps.contains(packageName)

                    val isEnabled = when (selectAppsModes) {
                        SelectAppsModes.ALLOW_ADD -> {
                            !isSelected || addedInSession.contains(packageName)
                        }
                        SelectAppsModes.ALLOW_REMOVE -> {
                            (specialChosenApp == null && isSelected) || specialChosenApp == packageName
                        }
                        SelectAppsModes.ALLOW_ADD_AND_REMOVE -> {
                            true
                        }
                    }

                    Checkbox(
                        checked = isSelected,
                        enabled = isEnabled,
                        onCheckedChange = { _ ->
                            handleAppSelection(packageName)
                        }
                    )
                    Text(
                        text = appName,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DistractionToolPricingSetupCard(
    adderPrice: Int,
    removerPrice: Int,
    onAdderPriceChange: (Int) -> Unit,
    onRemoverPriceChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("distraction_tool_pricing_setup_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Commitment & Tool Pricing Setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Set how many coins/gems it costs to buy or use the Distraction Adder and Remover tools later in the Store or Usage screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 1. Distraction Adder (0 to 10, default 0)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Image(
                        painter = painterResource(InventoryItem.DISTRACTION_ADDER.icon),
                        contentDescription = "Distraction Adder",
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Distraction Adder Cost",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "0 to 10 coins • Default: 0 (Free)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.coin_icon),
                            contentDescription = "Coins",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (adderPrice == 0) "0 (Free)" else "$adderPrice",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0, 2, 5, 10).forEach { preset ->
                    FilterChip(
                        selected = adderPrice == preset,
                        onClick = { onAdderPriceChange(preset) },
                        label = {
                            Text(if (preset == 0) "0 (Free)" else "$preset")
                        },
                        modifier = Modifier.testTag("adder_price_chip_$preset")
                    )
                }
            }

            Slider(
                value = adderPrice.toFloat(),
                onValueChange = { onAdderPriceChange(it.roundToInt().coerceIn(0, 10)) },
                valueRange = 0f..10f,
                steps = 9,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("adder_price_slider")
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 2. Distraction Remover (20 to 500, recommended 50 to 100, default 50)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Image(
                        painter = painterResource(InventoryItem.DISTRACTION_REMOVER.icon),
                        contentDescription = "Distraction Remover",
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Distraction Remover Cost",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Min 20 up to 500 • Recommended: 50–100",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.coin_icon),
                            contentDescription = "Coins",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$removerPrice",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val removerPresets = listOf(
                    20 to "20 (Min)",
                    50 to "50 (Default)",
                    100 to "100 (Rec)",
                    250 to "250",
                    500 to "500 (Max)"
                )
                removerPresets.forEach { (preset, label) ->
                    FilterChip(
                        selected = removerPrice == preset,
                        onClick = { onRemoverPriceChange(preset) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("remover_price_chip_$preset")
                    )
                }
            }

            Slider(
                value = removerPrice.toFloat(),
                onValueChange = { raw ->
                    val stepped = ((raw / 5f).roundToInt() * 5).coerceIn(20, 500)
                    onRemoverPriceChange(stepped)
                },
                valueRange = 20f..500f,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remover_price_slider")
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(10.dp)
            ) {
                Text(
                    text = when {
                        removerPrice >= 300 -> "Ironclad Prevention ($removerPrice coins): Makes removing an app from the blocklist virtually impossible on impulse."
                        removerPrice in 50..100 -> "Recommended Range ($removerPrice coins): Strong accountability while still allowing emergency unblocking after completing quests."
                        else -> "Custom Commitment ($removerPrice coins): Requires $removerPrice coins or 1 Distraction Remover tool to unblock an app."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

