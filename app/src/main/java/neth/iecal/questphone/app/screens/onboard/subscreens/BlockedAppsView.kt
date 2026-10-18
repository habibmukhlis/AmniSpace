package neth.iecal.questphone.app.screens.onboard.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import neth.iecal.questphone.app.screens.onboard.OnboarderViewModel

@Composable
fun BlockedAppsView(viewModel: OnboarderViewModel) {
    val context = LocalContext.current
    val adderPrice by viewModel.distractionAdderPrice.collectAsState()
    val removerPrice by viewModel.distractionRemoverPrice.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome Back",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Text(
            text = "You previously blocked the following apps. Configure your Distraction Adder & Remover commitment prices below:",
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        val pm = context.packageManager
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                DistractionToolPricingSetupCard(
                    adderPrice = adderPrice,
                    removerPrice = removerPrice,
                    onAdderPriceChange = { viewModel.updateAdderPrice(it) },
                    onRemoverPriceChange = { viewModel.updateRemoverPrice(it) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(viewModel.getDistractingApps().toList()) {
                val appName = try {
                    pm.getApplicationInfo(it, 0).loadLabel(pm).toString()
                } catch (e: Exception) {
                    it
                }
                Text(appName, Modifier.padding(vertical = 6.dp))
            }
        }
    }
}