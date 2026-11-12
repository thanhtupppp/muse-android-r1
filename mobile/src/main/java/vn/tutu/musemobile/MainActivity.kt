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
import vn.tutu.musemobile.domain.ProvisioningViewModel
import vn.tutu.musemobile.ui.AddR1Screen
import vn.tutu.musemobile.ui.BluetoothPairingScreen
import vn.tutu.musemobile.ui.ProvisioningScreen
import vn.tutu.musemobile.ui.WifiPickerScreen

class MainActivity : ComponentActivity() {

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { }

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
                                onR1Found = {
                                    navController.navigate("bluetooth_pairing")
                                }
                            )
                        }

                        composable("bluetooth_pairing") {
                            BluetoothPairingScreen(
                                viewModel = viewModel,
                                onDeviceSelected = {
                                    navController.navigate("wifi_picker")
                                },
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("wifi_picker") {
                            WifiPickerScreen(
                                viewModel = viewModel,
                                onBack = {
                                    navController.popBackStack()
                                },
                                onProvisioningStarted = {
                                    navController.navigate("provisioning")
                                }
                            )
                        }

                        composable("provisioning") {
                            ProvisioningScreen(
                                viewModel = viewModel,
                                onBack = {
                                    navController.popBackStack()
                                },
                                onDone = {
                                    navController.navigate("add_r1") {
                                        popUpTo("add_r1") { inclusive = true }
                                    }
                                }
                            )
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
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            locationPermissionLauncher.launch(
                missing.toTypedArray()
            )
        }
    }
}
