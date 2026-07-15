package com.example.raybanvision.wearables

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.types.DeviceCompatibility
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WearablesViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        private const val TAG = "WearablesViewModel"
    }

    private val repository = WearablesRepository.getInstance(application)

    private val _uiState = MutableStateFlow(WearablesUiState())
    val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

    private var observingStarted = false
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "State observation failed", t)
    }

    fun startObserving() {
        if (observingStarted) return
        observingStarted = true

        repository.startMonitoring()

        viewModelScope.launch(exceptionHandler) {
            repository.registrationState.collect { state ->
                _uiState.update { it.copy(registrationState = state) }
            }
        }
        viewModelScope.launch(exceptionHandler) {
            repository.devices.collect { devices ->
                _uiState.update { it.copy(devices = devices) }
            }
        }
        viewModelScope.launch(exceptionHandler) {
            repository.devicesMetadata.collect { metadata ->
                _uiState.update {
                    it.copy(
                        devicesMetadata = metadata,
                        isFirmwareUpdateRequired = metadata.values.any { device ->
                            device.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED
                        },
                    )
                }
            }
        }
    }

    fun startRegistration(activity: Activity) {
        repository.startRegistration(activity)
    }

    fun openFirmwareUpdate(activity: Activity) {
        com.meta.wearable.dat.core.Wearables.openFirmwareUpdate(activity)
            .onFailure { error, _ -> Log.e(TAG, "Firmware update failed: ${error.description}") }
    }
}
