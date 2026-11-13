package vn.tutu.musemobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import vn.tutu.musemobile.domain.ProvisioningViewModel
import vn.tutu.musemobile.ui.BluetoothPairingScreen
import vn.tutu.musemobile.ui.DiagnosticsScreen
import vn.tutu.musemobile.ui.HomeScreen
import vn.tutu.musemobile.ui.MusePairScreen
import vn.tutu.musemobile.ui.ProvisioningScreen
import vn.tutu.musemobile.ui.SetupScreen
import vn.tutu.musemobile.ui.WifiPickerScreen
import vn.tutu.musemobile.ui.theme.MuseMobileTheme

private data class TabDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val Tabs = listOf(
    TabDestination("home", "Tổng quan", Icons.Filled.Home),
    TabDestination("setup", "Cài đặt", Icons.Filled.Settings),
    TabDestination("muse", "Muse AI", Icons.Filled.SmartToy),
    TabDestination("diagnostics", "Chẩn đoán", Icons.Filled.Build)
)

private val SubRouteTitles = mapOf(
    "bluetooth_pairing" to "Kết nối Bluetooth",
    "wifi_picker" to "Chọn Wi‑Fi nhà",
    "provisioning" to "Đang cấu hình Wi‑Fi"
)

class MainActivity : ComponentActivity() {

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MuseMobileTheme {
                MuseMobileApp(onRequestWifiPermission = ::requestWifiPermissions)
            }
        }
    }

    private fun requestWifiPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            locationPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MuseMobileApp(onRequestWifiPermission: () -> Unit) {
    val viewModel: ProvisioningViewModel = viewModel()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTabRoute = Tabs.any { it.route == currentRoute }
    val title = when {
        isTabRoute -> Tabs.first { it.route == currentRoute }.label
        currentRoute != null -> SubRouteTitles[currentRoute] ?: "Muse R1"
        else -> "Muse R1"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isTabRoute) "Muse R1 • $title" else title,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (!isTabRoute && currentRoute != null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Quay lại"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (isTabRoute) {
                NavigationBar {
                    Tabs.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(imageVector = tab.icon, contentDescription = tab.label)
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn() + slideInHorizontally { it / 6 } },
            exitTransition = { fadeOut() + slideOutHorizontally { -it / 6 } },
            popEnterTransition = { fadeIn() + slideInHorizontally { -it / 6 } },
            popExitTransition = { fadeOut() + slideOutHorizontally { it / 6 } }
        ) {
            composable("home") {
                HomeScreen(viewModel = viewModel)
            }
            composable("setup") {
                SetupScreen(
                    viewModel = viewModel,
                    onOpenBluetooth = { navController.navigate("bluetooth_pairing") },
                    onOpenWifi = { navController.navigate("wifi_picker") }
                )
            }
            composable("muse") {
                MusePairScreen(viewModel = viewModel)
            }
            composable("diagnostics") {
                DiagnosticsScreen(
                    viewModel = viewModel,
                    onRequestWifiPermission = onRequestWifiPermission
                )
            }
            composable("bluetooth_pairing") {
                BluetoothPairingScreen(
                    viewModel = viewModel,
                    onDeviceSelected = { navController.navigate("wifi_picker") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("wifi_picker") {
                WifiPickerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onProvisioningStarted = { navController.navigate("provisioning") }
                )
            }
            composable("provisioning") {
                ProvisioningScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onDone = {
                        navController.navigate("muse") {
                            popUpTo("home") { inclusive = false }
                        }
                    }
                )
            }
        }
    }
}
