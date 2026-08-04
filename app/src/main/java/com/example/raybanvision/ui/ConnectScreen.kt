package com.example.raybanvision.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.raybanvision.BuildConfig
import com.example.raybanvision.R
import com.example.raybanvision.ui.components.ShoplyButton
import com.example.raybanvision.ui.components.ShoplyButtonVariant
import com.example.raybanvision.ui.theme.Grey700
import com.example.raybanvision.ui.theme.ShoplyDimens
import com.example.raybanvision.ui.theme.ShoplyType
import com.example.raybanvision.ui.theme.Yellow50
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
    onSavedLinksClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val selectedDevice = uiState.firstConnectableDisplayDevice()
    val primaryAction = {
        when {
            uiState.registrationState == RegistrationState.UNAVAILABLE ||
                uiState.registrationState == RegistrationState.REGISTERING -> Unit
            !uiState.isRegistered -> onRegisterClick()
            uiState.isFirmwareUpdateRequired -> onFirmwareUpdateClick()
            selectedDevice != null -> onDeviceSelected(selectedDevice.key)
            else -> onRegisterClick()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Yellow50)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(64.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.shoply_logo),
                contentDescription = "Shoply",
                modifier = Modifier
                    .fillMaxWidth(0.46f)
                    .height(134.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = "Shop smarter, Spend wiser",
                style = ShoplyType.Subheading,
                color = Grey700,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.weight(1.45f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = ShoplyDimens.Space900),
            verticalArrangement = Arrangement.spacedBy(ShoplyDimens.Space500),
        ) {
            ShoplyButton(
                label = if (uiState.isFirmwareUpdateRequired) "펌웨어 업데이트" else "글래스 연동하기",
                onClick = primaryAction,
                modifier = Modifier.fillMaxWidth(),
                variant = ShoplyButtonVariant.BrandSolid,
                icon = painterResource(R.drawable.ic_glasses),
            )
            ShoplyButton(
                label = "스마트폰으로 시작",
                onClick = primaryAction,
                modifier = Modifier.fillMaxWidth(),
                variant = ShoplyButtonVariant.NeutralSolid,
                icon = painterResource(R.drawable.ic_smartphone),
            )
            ShoplyButton(
                label = "저장한 링크 보기",
                onClick = onSavedLinksClick,
                modifier = Modifier.fillMaxWidth(),
                variant = ShoplyButtonVariant.SubtleSolid,
                icon = painterResource(R.drawable.ic_link),
            )
        }
    }
}

private fun WearablesUiState.firstConnectableDisplayDevice(): Map.Entry<DeviceIdentifier, Device>? {
    return devicesMetadata.entries.firstOrNull { (_, device) ->
        val connected = device.linkState == LinkState.CONNECTED
        val compatible = device.compatibility == DeviceCompatibility.COMPATIBLE
        val displayCapable = device.deviceType.isDisplayCapable || BuildConfig.DEBUG
        displayCapable && compatible && (connected || BuildConfig.DEBUG)
    }
}
