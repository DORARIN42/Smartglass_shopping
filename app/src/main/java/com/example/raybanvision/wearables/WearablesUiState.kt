package com.example.raybanvision.wearables

import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState

data class WearablesUiState(
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val devices: Set<DeviceIdentifier> = emptySet(),
    val devicesMetadata: Map<DeviceIdentifier, Device> = emptyMap(),
    val isFirmwareUpdateRequired: Boolean = false,
) {
    val isRegistered: Boolean get() = registrationState == RegistrationState.REGISTERED

    val hasConnectedDevice: Boolean
        get() = devicesMetadata.values.any { it.linkState == LinkState.CONNECTED }
}
