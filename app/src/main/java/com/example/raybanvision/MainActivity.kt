package com.example.raybanvision

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.CAMERA
import android.Manifest.permission.INTERNET
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.raybanvision.data.SAMPLE_RESULT
import com.example.raybanvision.session.SavedLinksStore
import com.example.raybanvision.mock.MockGlassesInitializer
import com.example.raybanvision.session.SessionViewModel
import com.example.raybanvision.ui.ConnectScreen
import com.example.raybanvision.ui.MainScreen
import com.example.raybanvision.ui.SavedLinksScreen
import com.example.raybanvision.wearables.WearablesViewModel
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val wearablesViewModel: WearablesViewModel by viewModels()
    private val sessionViewModel: SessionViewModel by viewModels()
    private var pendingDatCameraPermissionAction: (() -> Unit)? = null

    private val datCameraPermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result
                .onSuccess { status ->
                    if (status == PermissionStatus.Granted) {
                        pendingDatCameraPermissionAction?.invoke()
                    } else {
                        Toast.makeText(this, "Meta AI camera permission is required.", Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure { error, _ ->
                    Toast.makeText(this, "DAT camera permission failed: ${error.description}", Toast.LENGTH_LONG).show()
                }
            pendingDatCameraPermissionAction = null
        }

    private val permissionsLauncher = registerForActivityResult(RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            // 물리 글라스 테스트가 기본이다. 목 디바이스가 필요하면 use_mock_glasses=true 로 켠다.
            // MDK 활성화는 반드시 Wearables.initialize 이전에 해야 한다.
            if (BuildConfig.USE_MOCK_GLASSES) {
                MockGlassesInitializer.enable(this)
            }
            Wearables.initialize(this)
                .onFailure { error, _ ->
                    Toast.makeText(this, "DAT 초기화 실패: ${error.description}", Toast.LENGTH_LONG).show()
                }
            wearablesViewModel.startObserving()
            if (BuildConfig.USE_MOCK_GLASSES) {
                MockGlassesInitializer.pairMockGlasses(this)
            }
        } else {
            Toast.makeText(this, "모든 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SavedLinksStore.init(this)

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val wearablesUiState by wearablesViewModel.uiState.collectAsStateWithLifecycle()
                val sessionUiState by sessionViewModel.uiState.collectAsStateWithLifecycle()
                val previewFrame by sessionViewModel.previewFrame.collectAsStateWithLifecycle()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "connect",
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable("connect") {
                            ConnectScreen(
                                uiState = wearablesUiState,
                                onRegisterClick = { wearablesViewModel.startRegistration(this@MainActivity) },
                                onDeviceSelected = { deviceId ->
                                    startSessionAfterDatCameraPermission(deviceId) {
                                        navController.navigate("main")
                                    }
                                },
                                onFirmwareUpdateClick = { wearablesViewModel.openFirmwareUpdate(this@MainActivity) },
                                onSavedLinksClick = { navController.navigate("saved-links") },
                            )
                        }
                        composable("saved-links") {
                            SavedLinksScreen(
                                onBack = { navController.popBackStack() },
                                onRetake = { navController.popBackStack("connect", inclusive = false) },
                            )
                        }
                        composable("main") {
                            MainScreen(
                                uiState = sessionUiState,
                                previewFrame = previewFrame,
                                onCaptureClick = { sessionViewModel.capturePhoto() },
                                onRetakeClick = { sessionViewModel.retakePhoto() },
                                onSearchClick = { sessionViewModel.submitForSearch() },
                                onPriceComparisonClick = { sessionViewModel.showPriceComparison() },
                                onSendSampleResult = { sessionViewModel.displayResult(SAMPLE_RESULT) },
                                onDisconnect = {
                                    sessionViewModel.stopSession()
                                    navController.popBackStack()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        permissionsLauncher.launch(arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, CAMERA, INTERNET))
    }

    private fun startSessionAfterDatCameraPermission(
        deviceId: DeviceIdentifier,
        onStarted: () -> Unit,
    ) {
        lifecycleScope.launch {
            Wearables.checkPermissionStatus(Permission.CAMERA)
                .onSuccess { status ->
                    if (status == PermissionStatus.Granted) {
                        sessionViewModel.startSession(deviceId)
                        onStarted()
                    } else {
                        pendingDatCameraPermissionAction = {
                            sessionViewModel.startSession(deviceId)
                            onStarted()
                        }
                        datCameraPermissionLauncher.launch(Permission.CAMERA)
                    }
                }
                .onFailure { error, _ ->
                    pendingDatCameraPermissionAction = {
                        sessionViewModel.startSession(deviceId)
                        onStarted()
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Request Meta AI camera permission: ${error.description}",
                        Toast.LENGTH_LONG,
                    ).show()
                    datCameraPermissionLauncher.launch(Permission.CAMERA)
                }
        }
    }
}
