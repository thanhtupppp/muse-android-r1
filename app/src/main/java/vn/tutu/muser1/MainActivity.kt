package vn.tutu.muser1

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ProvisionScreen(
                    initialSsid = intent?.getStringExtra(EXTRA_SSID).orEmpty(),
                    initialPassword = intent?.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    autoConnect = intent?.getStringExtra(EXTRA_SSID) != null
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent {
            MaterialTheme {
                ProvisionScreen(
                    initialSsid = intent.getStringExtra(EXTRA_SSID).orEmpty(),
                    initialPassword = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    autoConnect = true
                )
            }
        }
    }

    companion object {
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
    }
}

@Composable
private fun ProvisionScreen(
    initialSsid: String,
    initialPassword: String,
    autoConnect: Boolean
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var ssid by remember { mutableStateOf(initialSsid) }
    var password by remember { mutableStateOf(initialPassword) }
    var status by remember {
        mutableStateOf("Nhập Wi‑Fi nhà rồi chọn Kết nối.")
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Muse R1 Wi‑Fi Setup",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("SSID Wi‑Fi") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mật khẩu Wi‑Fi") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val wifiManager = context.getSystemService(WifiManager::class.java)
                    status = WifiProvisioner.provision(context, wifiManager, ssid, password)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Kết nối Wi‑Fi")
            }

            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }) {
                Text("Mở cài đặt Wi‑Fi")
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = status)

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = NetworkStateObserver.currentSummary(context))
        }
    }

    if (autoConnect && initialSsid.isNotBlank()) {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        status = WifiProvisioner.provision(context, wifiManager, initialSsid, initialPassword)
    }
}
