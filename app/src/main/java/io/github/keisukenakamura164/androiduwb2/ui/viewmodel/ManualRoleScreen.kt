package io.github.keisukenakamura164.androiduwb2.ui.viewmodel

import android.content.Context
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.keisukenakamura164.androiduwb2.UwbControllerParams
import io.github.keisukenakamura164.androiduwb2.ble.BleCentral
import io.github.keisukenakamura164.androiduwb2.ble.BlePeripheral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 手動で役割を選択するViewModel
 */
class ManualRoleViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ManualRoleUiState())
    val uiState = _uiState.asStateFlow()

    private var rangingJob: Job? = null

    /**
     * Controllerとして起動（デバイスA）
     */
    fun startAsController(context: Context) {
        if (rangingJob?.isActive == true) return

        rangingJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(role = "待機中 (Controller)") }
                android.util.Log.d("ManualRoleViewModel", "Starting as Controller")

                val uwbManager = UwbManager.createInstance(context)
                val controllerSession = uwbManager.controllerSessionScope()

                val fixedComplexChannel = UwbComplexChannel(9, 0)

                val params = UwbControllerParams(
                    address = controllerSession.localAddress.address,
                    channel = fixedComplexChannel.channel,
                    preambleIndex = fixedComplexChannel.preambleIndex,
                )
                val encodedParams = UwbControllerParams.encode(params)

                android.util.Log.d("ManualRoleViewModel", "Starting BLE peripheral...")
                android.util.Log.d("ManualRoleViewModel", "Controller params - channel: ${params.channel}, address: ${params.address.contentToString()}")

                // BLEアドバタイズを開始し、Controleeからのアドレス書き込みを待つ
                val controleeAddress = BlePeripheral.startPeripheralAndWaitForAddress(
                    context = context,
                    onCharacteristicReadRequest = {
                        android.util.Log.d("ManualRoleViewModel", "Characteristic read requested")
                        encodedParams
                    }
                )

                android.util.Log.d("ManualRoleViewModel", "Received Controlee address: ${controleeAddress.contentToString()}")
                _uiState.update { it.copy(role = "Controller (接続成功)") }

                val rangingParameters = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
                    complexChannel = fixedComplexChannel,
                    peerDevices = listOf(UwbDevice.createForAddress(controleeAddress)),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
                    sessionId = 0,
                    sessionKeyInfo = null,
                    subSessionId = 0,
                    subSessionKeyInfo = null
                )

                android.util.Log.d("ManualRoleViewModel", "Starting UWB ranging as Controller")
                controllerSession.prepareSession(rangingParameters).collect(::handleRangingResult)

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(role = "エラー: ${e.message}") }
                android.util.Log.e("ManualRoleViewModel", "Failed as Controller", e)
            } finally {
                BlePeripheral.stop()
            }
        }
    }

    /**
     * Controleeとして起動（デバイスB）
     */
    fun startAsControlee(context: Context) {
        if (rangingJob?.isActive == true) return

        rangingJob = viewModelScope.launch {
            _uiState.update { it.copy(role = "検索中 (Controlee)") }
            android.util.Log.d("ManualRoleViewModel", "Starting as Controlee")

            val bleCentral = BleCentral(context)
            try {
                android.util.Log.d("ManualRoleViewModel", "Connecting and reading characteristic...")
                val paramsByteArray = bleCentral.connectAndReadCharacteristic()
                android.util.Log.d("ManualRoleViewModel", "Read params: ${paramsByteArray.size} bytes")

                val params = UwbControllerParams.decode(paramsByteArray)
                android.util.Log.d("ManualRoleViewModel", "Decoded params - channel: ${params.channel}, address: ${params.address.contentToString()}")

                val uwbManager = UwbManager.createInstance(context)
                val address = uwbManager.controleeSessionScope().localAddress.address
                android.util.Log.d("ManualRoleViewModel", "Writing address: ${address.contentToString()}")
                bleCentral.writeCharacteristic(address)

                _uiState.update { it.copy(role = "Controlee (接続成功)") }
                android.util.Log.d("ManualRoleViewModel", "Successfully became Controlee")

                val controleeSession = uwbManager.controleeSessionScope()
                val rangingParameters = RangingParameters(
                    uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
                    complexChannel = UwbComplexChannel(params.channel, params.preambleIndex),
                    peerDevices = listOf(UwbDevice.createForAddress(params.address)),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
                    sessionId = 0,
                    sessionKeyInfo = null,
                    subSessionId = 0,
                    subSessionKeyInfo = null
                )

                android.util.Log.d("ManualRoleViewModel", "Starting UWB ranging as Controlee")
                controleeSession.prepareSession(rangingParameters).collect(::handleRangingResult)

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("ManualRoleViewModel", "Controlee failed: ${e.message}", e)
                _uiState.update { it.copy(role = "エラー: ${e.message}") }
            } finally {
                bleCentral.destroy()
            }
        }
    }

    /**
     * 停止
     */
    fun stop() {
        rangingJob?.cancel()
        rangingJob = null
        BlePeripheral.stop()
        _uiState.update { ManualRoleUiState() }
        android.util.Log.d("ManualRoleViewModel", "Stopped")
    }

    private fun handleRangingResult(rangingResult: RangingResult) {
        when (rangingResult) {
            is RangingResult.RangingResultPosition -> {
                android.util.Log.d("ManualRoleViewModel", "Ranging result - distance: ${rangingResult.position.distance?.value}")
                _uiState.update { currentState ->
                    currentState.copy(
                        distance = rangingResult.position.distance?.value,
                        azimuth = rangingResult.position.azimuth?.value,
                        elevation = rangingResult.position.elevation?.value
                    )
                }
            }
            is RangingResult.RangingResultPeerDisconnected -> {
                android.util.Log.w("ManualRoleViewModel", "Peer disconnected")
                _uiState.update {
                    it.copy(
                        distance = null,
                        azimuth = null,
                        elevation = null
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        rangingJob?.cancel()
        BlePeripheral.stop()
    }
}

data class ManualRoleUiState(
    val distance: Float? = null,
    val azimuth: Float? = null,
    val elevation: Float? = null,
    val role: String = "待機中..."
)