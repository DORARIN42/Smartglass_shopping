package com.example.raybanvision.wearables

import android.app.Activity
import android.content.Context
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "WearablesRepository"

class WearablesRepository private constructor(
    private val applicationContext: Context,
    private val scope: CoroutineScope,
) {
    private val lock = Object()
    private var monitoringStarted = false

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.UNAVAILABLE)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _devices = MutableStateFlow<Set<DeviceIdentifier>>(emptySet())
    val devices: StateFlow<Set<DeviceIdentifier>> = _devices.asStateFlow()

    // DeviceIdentifier → Device (metadata per device, collected as individual flows)
    private val _devicesMetadata = MutableStateFlow<Map<DeviceIdentifier, Device>>(emptyMap())
    val devicesMetadata: StateFlow<Map<DeviceIdentifier, Device>> = _devicesMetadata.asStateFlow()

    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Wearables monitoring failed", t)
    }

    fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        scope.launch(exceptionHandler) {
            Wearables.registrationState.collect { _registrationState.value = it }
        }
        scope.launch(exceptionHandler) {
            Wearables.registrationErrorStream.collect { error ->
                Log.e(TAG, error.getLocalizedDescription(applicationContext))
            }
        }
        scope.launch(exceptionHandler) {
            Wearables.devices.collect { identifiers -> updateDevices(identifiers) }
        }
    }

    private fun updateDevices(identifiers: Set<DeviceIdentifier>) {
        _devices.value = identifiers
        val current = _devicesMetadata.value
        val removed = current.keys - identifiers
        val added = identifiers - current.keys

        if (removed.isNotEmpty()) {
            _devicesMetadata.update { it.filterKeys { key -> key !in removed } }
        }
        for (id in added) {
            scope.launch(exceptionHandler) {
                Wearables.devicesMetadata[id]?.collect { device -> updateMetadata(id, device) }
            }
        }
    }

    private fun updateMetadata(id: DeviceIdentifier, device: Device) {
        synchronized(lock) { _devicesMetadata.update { it.toMutableMap().apply { put(id, device) } } }
    }

    fun startRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    companion object {
        @Volatile private var instance: WearablesRepository? = null

        fun getInstance(context: Context): WearablesRepository =
            instance ?: synchronized(this) {
                instance ?: WearablesRepository(
                    context.applicationContext,
                    CoroutineScope(SupervisorJob() + Dispatchers.Main),
                ).also { instance = it }
            }
    }
}
