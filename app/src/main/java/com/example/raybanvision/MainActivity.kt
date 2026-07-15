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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.raybanvision.data.SAMPLE_RESULT
import com.example.raybanvision.mock.MockGlassesInitializer
import com.example.raybanvision.session.SessionViewModel
import com.example.raybanvision.ui.ConnectScreen
import com.example.raybanvision.ui.MainScreen
import com.example.raybanvision.wearables.WearablesViewModel
import com.meta.wearable.dat.core.Wearables

class MainActivity : ComponentActivity() {

    private val wearablesViewModel: WearablesViewModel by viewModels()
    private val sessionViewModel: SessionViewModel by viewModels()

    private val permissionsLauncher = registerForActivityResult(RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            // debug 빌드: 물리 글라스 없이 폰을 목 디바이스로 사용 (release 에서는 no-op).
            // MDK 활성화는 반드시 Wearables.initialize 이전에 해야 한다.
            MockGlassesInitializer.enable(this)
            Wearables.initialize(this)
                .onFailure { error, _ ->
                    Toast.makeText(this, "DAT 초기화 실패: ${error.description}", Toast.LENGTH_LONG).show()
                }
            wearablesViewModel.startObserving()
            MockGlassesInitializer.pairMockGlasses(this)
        } else {
            Toast.makeText(this, "모든 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                                    sessionViewModel.startSession(deviceId)
                                    navController.navigate("main")
                                },
                                onFirmwareUpdateClick = { wearablesViewModel.openFirmwareUpdate(this@MainActivity) },
                            )
                        }
                        composable("main") {
                            MainScreen(
                                uiState = sessionUiState,
                                previewFrame = previewFrame,
                                onCaptureClick = { sessionViewModel.capturePhoto() },
                                onRetakeClick = { sessionViewModel.retakePhoto() },
                                onSearchClick = { sessionViewModel.submitForSearch() },
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
}
