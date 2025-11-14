package io.github.keisukenakamura164.androiduwb2.ui.viewmodel

import android.content.Context
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.keisukenakamura164.androiduwb2.UwbControllerParams
import io.github.keisukenakamura164.androiduwb2.ble.BlePeripheral
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

class ControllerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UwbUiState>(UwbUiState())
    val uiState = _uiState.asStateFlow()

    // 画面遷移イベントを通知するためのFlow（一度きりのイベントに適している）
    private val _navigationEvent = MutableSharedFlow<String>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    // 関数名は startUwbSession のままで問題ありません
    fun startUwbAndBleSession(context: Context) {
        viewModelScope.launch {
            // UWBマネージャーを作成
            val uwbManager = UwbManager.createInstance(context)
            val controllerSession = uwbManager.controllerSessionScope()

            // Controleeへ送るパラメータを生成
            val sessionId = Random.nextInt()
            val sessionKeyInfo = Random.nextBytes(8)
            val uwbControllerParams = UwbControllerParams(
                address = controllerSession.localAddress.address,
                channel = controllerSession.uwbComplexChannel.channel,
                preambleIndex = controllerSession.uwbComplexChannel.preambleIndex,
                sessionId = sessionId,
                sessionKeyInfo = sessionKeyInfo
            )
            val encodeHostParameter = UwbControllerParams.Companion.encode(uwbControllerParams)

            // Controleeのアドレスを受け取るためのFlow
            val controleeAddressFlow = MutableStateFlow<ByteArray?>(null)

            // BLEアドバタイズを開始
            val peripheralJob = launch {
                BlePeripheral.startPeripheralAndAdvertising(
                    context = context,
                    onCharacteristicReadRequest = { encodeHostParameter },
                    onCharacteristicWriteRequest = { controleeAddressFlow.value = it }
                )
            }

            // Controleeからアドレスが送られてくるまで待つ
            val controleeAddress = controleeAddressFlow.filterNotNull().first()
            peripheralJob.cancel()

            // UWB測距を開始
            val rangingParameters = RangingParameters(
                uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
                complexChannel = controllerSession.uwbComplexChannel,
                peerDevices = listOf(UwbDevice.createForAddress(controleeAddress)),
                updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
                sessionId = sessionId,
                sessionKeyInfo = sessionKeyInfo,
                subSessionId = 0,
                subSessionKeyInfo = null
            )
            controllerSession.prepareSession(rangingParameters).collect { result ->
                if (result is RangingResult.RangingResultPosition) {
                    val distance = result.position.distance?.value
                    val azimuth = result.position.azimuth?.value

                    _uiState.update { it.copy(distance = distance, azimuth = azimuth) }
                    if (distance != null && distance < 10.0f) {
                        _navigationEvent.emit("success")
                    }
                }
            }
        }
    }
}

// 状態をまとめて管理するデータクラス
data class UwbUiState(val distance: Float? = null, val azimuth: Float? = null)
