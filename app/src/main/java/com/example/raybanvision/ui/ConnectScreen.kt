package com.example.raybanvision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.raybanvision.BuildConfig
import com.example.raybanvision.wearables.WearablesUiState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState

@Composable
fun ConnectScreen(
    uiState: WearablesUiState,
    onRegisterClick: () -> Unit,
    onDeviceSelected: (DeviceIdentifier) -> Unit,
    onFirmwareUpdateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("RayBan Vision", style = MaterialTheme.typography.headlineMedium)
        HorizontalDivider()

        when {
            uiState.registrationState == RegistrationState.UNAVAILABLE ||
            uiState.registrationState == RegistrationState.REGISTERING -> {
                CircularProgressIndicator()
                Text("초기화 중...")
            }

            !uiState.isRegistered -> {
                Text("Meta AI 앱에서 이 앱을 등록해주세요.")
                Button(onClick = onRegisterClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Meta AI에 등록")
                }
            }

            else -> {
                Text("디스플레이 글라스를 선택하세요", style = MaterialTheme.typography.titleMedium)

                if (uiState.isFirmwareUpdateRequired) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("글라스 펌웨어 업데이트가 필요합니다")
                            OutlinedButton(onClick = onFirmwareUpdateClick) { Text("업데이트") }
                        }
                    }
                }

                // 실제 빌드는 디스플레이 지원 글라스만 노출. debug 는 MDK 목 디바이스(카메라만
                // 지원, 디스플레이 미지원)로 촬영을 테스트하기 위해 모든 기기를 노출한다.
                val displayCapableDevices = uiState.devicesMetadata.entries
                    .filter { (_, device) -> device.deviceType.isDisplayCapable || BuildConfig.DEBUG }

                if (displayCapableDevices.isEmpty()) {
                    Text(
                        "연결된 디스플레이 글라스가 없습니다.\nBluetooth를 확인하고 글라스 전원을 켜주세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(displayCapableDevices) { (id, device) ->
                            DeviceCard(
                                id = id,
                                device = device,
                                onSelect = { onDeviceSelected(id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    id: DeviceIdentifier,
    device: Device,
    onSelect: () -> Unit,
) {
    val isConnected = device.linkState == LinkState.CONNECTED
    val isCompatible = device.compatibility == DeviceCompatibility.COMPATIBLE

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${device.deviceType.description} · ${device.linkState.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // debug 에서는 목 디바이스 상태 편차와 무관하게 선택 가능하도록 허용한다.
            Button(onClick = onSelect, enabled = (isConnected && isCompatible) || BuildConfig.DEBUG) {
                Text(
                    when {
                        !isCompatible -> "업데이트 필요"
                        !isConnected -> "연결 안됨"
                        else -> "연결"
                    }
                )
            }
        }
    }
}
