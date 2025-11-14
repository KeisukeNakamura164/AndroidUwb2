package io.github.keisukenakamura164.androiduwb2.ui.viewmodel

import android.content.Context
import androidx.core.uwb.RangingParameters
// import androidx.core.uwb.RangingPosition // UwbUiState を使うので不要になる
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.keisukenakamura164.androiduwb2.UwbControllerParams
import io.github.keisukenakamura164.androiduwb2.ble.BleCentral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update // update関数を使うために必要
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controlee側のUWBやBLEの処理を担当するViewModel
 */
class ControleeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UwbUiState())
    val uiState = _uiState.asStateFlow()

    /**
     * UWBとBLEのセッションを開始する
     */
    fun startUwbAndBleSession(context: Context) {
        viewModelScope.launch {

            // withContextは例外を発生させる可能性があるので、try-catchで囲むとより安全
            val uwbControllerParams = try {
                withContext(Dispatchers.IO) {
                    val uwbManager = UwbManager.createInstance(context)
                    val controleeSession = uwbManager.controleeSessionScope()
                    val addressByteArray = controleeSession.localAddress.address

                    val bleCentral = BleCentral(context)
                    bleCentral.connectGattServer()
                    val uwbControllerParamsByteArray = bleCentral.readCharacteristic()
                    val params = UwbControllerParams.decode(uwbControllerParamsByteArray)
                    bleCentral.writeCharacteristic(addressByteArray)
                    bleCentral.destroy()
                    params
                }
            } catch (e: Exception) {
                // BLE接続などでエラーが起きたらnullを返し、処理を中断
                e.printStackTrace()
                null
            }

            // パラメータ交換に失敗した場合は何もしない
            if (uwbControllerParams == null) {
                return@launch
            }

            // UWBマネージャーとセッションスコープを再度取得
            val uwbManager = UwbManager.createInstance(context)
            val controleeSession = uwbManager.controleeSessionScope()

            // UWB測距を開始
            val rangingParameters = RangingParameters(
                uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
                complexChannel = UwbComplexChannel(uwbControllerParams.channel, uwbControllerParams.preambleIndex),
                peerDevices = listOf(UwbDevice.createForAddress(uwbControllerParams.address)),
                updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
                sessionId = uwbControllerParams.sessionId,
                sessionKeyInfo = uwbControllerParams.sessionKeyInfo,
                subSessionId = 0,
                subSessionKeyInfo = null
            )
            controleeSession.prepareSession(rangingParameters).collect { rangingResult ->
                when (rangingResult) {
                    is RangingResult.RangingResultPosition -> {
                        // ★★★ ここを修正 ★★★
                        // 取得した位置情報を使って uiState を更新する
                        _uiState.update { currentState ->
                            currentState.copy(
                                distance = rangingResult.position.distance?.value,
                                azimuth = rangingResult.position.azimuth?.value
                            )
                        }
                    }

                    is RangingResult.RangingResultPeerDisconnected -> {
                        _uiState.value = UwbUiState()
                    }
                }
            }
        }
    }
}