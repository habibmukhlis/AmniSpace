package neth.iecal.questphone.app.screens.etc

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.window.Dialog
import neth.iecal.questphone.R
import neth.iecal.questphone.app.screens.onboard.subscreens.DistractionToolPricingSetupCard
import neth.iecal.questphone.backed.repositories.UserRepository
import nethical.questphone.data.game.InventoryItem

sealed class PendingDistractionAction {
    data class SingleToggle(
        val packageName: String,
        val appLabel: String,
        val isAdding: Boolean
    ) : PendingDistractionAction()

    data class BulkUpdate(
        val targetBlockedSet: Set<String>,
        val addedCount: Int,
        val removedCount: Int
    ) : PendingDistractionAction()
}

@Composable
fun DistractionToolGateDialog(
    action: PendingDistractionAction,
    userRepository: UserRepository,
    userCoins: Int,
    adderPrice: Int,
    removerPrice: Int,
    adderOwned: Int,
    removerOwned: Int,
    onDismiss: () -> Unit,
    onActionApproved: (feedbackMessage: String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("distraction_tool_gate_dialog")
        ) {
            when (action) {
                is PendingDistractionAction.SingleToggle -> {
                    val item = if (action.isAdding) {
                        InventoryItem.DISTRACTION_ADDER
                    } else {
                        InventoryItem.DISTRACTION_REMOVER
                    }
                    val toolPrice = if (action.isAdding) adderPrice else removerPrice
                    val ownedCount = if (action.isAdding) adderOwned else removerOwned
                    val canUseInventory = ownedCount > 0
                    val canBuyWithCoins = userCoins >= toolPrice

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (action.isAdding) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(item.icon),
                                contentDescription = item.simpleName,
                                modifier = Modifier.size(44.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = if (action.isAdding) {
                                "Add ${action.appLabel} to Blocklist"
                            } else {
                                "Remove ${action.appLabel} from Blocklist"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (action.isAdding) {
                                "Requires 1 ${item.simpleName} tool or $toolPrice coins."
                            } else {
                                "Unblocking a distracting app requires 1 ${item.simpleName} tool or your configured commitment cost of $toolPrice coins."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Balance & Inventory summary pill
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Tool Cost",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Image(
                                            painter = painterResource(R.drawable.coin_icon),
                                            contentDescription = "Coins",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "$toolPrice",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Your Balance",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Image(
                                            painter = painterResource(R.drawable.coin_icon),
                                            contentDescription = "Balance",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "$userCoins",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "In Inventory",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            painter = painterResource(R.drawable.baseline_inventory_24),
                                            contentDescription = "Inventory",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "$ownedCount",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        if (!canUseInventory && !canBuyWithCoins) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "You need ${toolPrice - userCoins} more coins (or 1 ${item.simpleName} in Inventory). Complete quests to earn coins!",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (canUseInventory) {
                                Button(
                                    onClick = {
                                        userRepository.deductFromInventory(item, 1)
                                        val verb = if (action.isAdding) "Added" else "Removed"
                                        onActionApproved(
                                            "$verb ${action.appLabel} (Used 1 ${item.simpleName} from Inventory)"
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("use_inventory_tool_button")
                                ) {
                                    Text(
                                        text = "Use 1 ${item.simpleName} (Owned: $ownedCount)",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            if (canBuyWithCoins) {
                                Button(
                                    onClick = {
                                        if (toolPrice > 0) {
                                            userRepository.useCoins(toolPrice)
                                        }
                                        val verb = if (action.isAdding) "Added" else "Removed"
                                        onActionApproved(
                                            "$verb ${action.appLabel} (-$toolPrice Coins)"
                                        )
                                    },
                                    colors = if (action.isAdding) {
                                        ButtonDefaults.buttonColors()
                                    } else {
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("instant_buy_use_tool_button")
                                ) {
                                    Text(
                                        text = if (toolPrice == 0) {
                                            "Confirm (Free)"
                                        } else {
                                            "Buy & Use Now ($toolPrice Coins)"
                                        },
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("cancel_tool_gate_button")
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }

                is PendingDistractionAction.BulkUpdate -> {
                    val addersNeeded = if (adderPrice > 0) action.addedCount else 0
                    val removersNeeded = action.removedCount

                    val addersFromInv = minOf(adderOwned, addersNeeded)
                    val addersToBuy = (addersNeeded - addersFromInv).coerceAtLeast(0)

                    val removersFromInv = minOf(removerOwned, removersNeeded)
                    val removersToBuy = (removersNeeded - removersFromInv).coerceAtLeast(0)

                    val totalCoinCost = (addersToBuy * adderPrice) + (removersToBuy * removerPrice)
                    val canAfford = userCoins >= totalCoinCost

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Confirm Blocklist Changes",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = buildString {
                                if (action.addedCount > 0) {
                                    append("Adding ${action.addedCount} app(s)")
                                    if (adderPrice == 0) append(" (Free)")
                                }
                                if (action.addedCount > 0 && action.removedCount > 0) {
                                    append(" • ")
                                }
                                if (action.removedCount > 0) {
                                    append("Removing ${action.removedCount} app(s)")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (addersFromInv > 0 || removersFromInv > 0) {
                                    Text(
                                        text = "Inventory Tools Used: " + listOfNotNull(
                                            if (addersFromInv > 0) "$addersFromInv Adder(s)" else null,
                                            if (removersFromInv > 0) "$removersFromInv Remover(s)" else null
                                        ).joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = "Coins Required: $totalCoinCost (Your Balance: $userCoins)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (canAfford) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }

                        if (!canAfford) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "You need ${totalCoinCost - userCoins} more coins to complete this blocklist update.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    if (addersFromInv > 0) {
                                        userRepository.deductFromInventory(
                                            InventoryItem.DISTRACTION_ADDER,
                                            addersFromInv
                                        )
                                    }
                                    if (removersFromInv > 0) {
                                        userRepository.deductFromInventory(
                                            InventoryItem.DISTRACTION_REMOVER,
                                            removersFromInv
                                        )
                                    }
                                    if (totalCoinCost > 0) {
                                        userRepository.useCoins(totalCoinCost)
                                    }
                                    onActionApproved("Blocklist updated (${action.targetBlockedSet.size} apps blocked)")
                                },
                                enabled = canAfford,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Confirm")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DistractionToolPricingSettingsDialog(
    userRepository: UserRepository,
    currentAdderPrice: Int,
    currentRemoverPrice: Int,
    userCoins: Int,
    removerOwned: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var draftAdderPrice by remember(currentAdderPrice) { mutableIntStateOf(currentAdderPrice) }
    var draftRemoverPrice by remember(currentRemoverPrice) { mutableIntStateOf(currentRemoverPrice) }

    val isLoweringPrice = draftAdderPrice < currentAdderPrice || draftRemoverPrice < currentRemoverPrice
    val hasChanges = draftAdderPrice != currentAdderPrice || draftRemoverPrice != currentRemoverPrice
    val canPayReductionWithTool = removerOwned > 0
    val canPayReductionWithCoins = userCoins >= currentRemoverPrice

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .testTag("distraction_tool_pricing_settings_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DistractionToolPricingSetupCard(
                    adderPrice = draftAdderPrice,
                    removerPrice = draftRemoverPrice,
                    onAdderPriceChange = { draftAdderPrice = it },
                    onRemoverPriceChange = { draftRemoverPrice = it }
                )

                if (isLoweringPrice) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Commitment Protection Active",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Increasing tool prices is always free, but lowering a tool's price requires 1 Distraction Remover tool (Owned: $removerOwned) or paying your current Remover cost ($currentRemoverPrice coins).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (!isLoweringPrice) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                userRepository.updateDistractionToolPrices(
                                    draftAdderPrice,
                                    draftRemoverPrice
                                )
                                Toast.makeText(
                                    context,
                                    "Updated tool prices (Adder: $draftAdderPrice, Remover: $draftRemoverPrice)",
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            },
                            enabled = hasChanges,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_tool_prices_free_button")
                        ) {
                            Text("Save (Free)")
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (canPayReductionWithTool) {
                            Button(
                                onClick = {
                                    userRepository.deductFromInventory(
                                        InventoryItem.DISTRACTION_REMOVER,
                                        1
                                    )
                                    userRepository.updateDistractionToolPrices(
                                        draftAdderPrice,
                                        draftRemoverPrice
                                    )
                                    Toast.makeText(
                                        context,
                                        "Used 1 Distraction Remover & updated prices",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Use 1 Remover Tool & Lower Price")
                            }
                        }

                        if (canPayReductionWithCoins) {
                            Button(
                                onClick = {
                                    userRepository.useCoins(currentRemoverPrice)
                                    userRepository.updateDistractionToolPrices(
                                        draftAdderPrice,
                                        draftRemoverPrice
                                    )
                                    Toast.makeText(
                                        context,
                                        "Paid $currentRemoverPrice coins & updated prices",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Pay $currentRemoverPrice Coins & Lower Price")
                            }
                        }

                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
