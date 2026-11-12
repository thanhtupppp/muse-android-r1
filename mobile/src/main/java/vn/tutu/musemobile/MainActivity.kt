package vn.tutu.musemobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import vn.tutu.musemobile.ui.AddR1Screen
import vn.tutu.musemobile.ui.ProvisioningScreen
import vn.tutu.musemobile.ui.WifiPickerScreen
import vn.tutu.musemobile.domain.ProvisioningViewModel

class MainActivity : ComponentActivity() {

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val viewModel: ProvisioningViewModel = viewModel()
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "add_r1"
                    ) {
                        composable("add_r1") {
                            AddR1Screen(
                                viewModel = viewModel,
                                onRequestWifiPermission = ::requestWifiPermissions,
                                onWifiSelected = { navController.navigate("provisioning") }
                            )
                        }

                        composable("wifi_picker") {
                            WifiPickerScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                                onWifiSelected = { navController.navigate("provisioning") }
                            )
                        }

                        composable("provisioning") {
                            ProvisioningScreen(viewModel = viewModel)
                        }
                    }
                }
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
