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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

/**
 * Controller / Controlee の役割を自動で決定し、UWBセッションを開始するViewModel
 */
class AutoRoleViewModel : ViewModel() {

    // UIの状態（距離、方位角、自分の役割）を管理する
    private val _uiState = MutableStateFlow(UwbRoleUiState())
    val uiState = _uiState.asStateFlow()

    private var rangingJob: Job? = null

    /**
     * BLEのスキャンとアドバタイズを同時に開始し、役割を決定する
     */
    fun startRoleDiscoveryAndUwbSession(context: Context) {
        if (rangingJob?.isActive == true) return

        rangingJob = viewModelScope.launch {
            // ランダムな遅延
            val randomDelay = Random.nextLong(500, 2000)
            android.util.Log.d("AutoRoleViewModel", "Random delay: $randomDelay ms")
            delay(randomDelay)

            try {
                // Controleeのタイムアウトを20秒に延長
                withTimeout(20_000L) {
                    runAsControlee(context)
                }
            } catch (e: TimeoutCancellationException) {
                android.util.Log.d("AutoRoleViewModel", "Controlee timeout, switching to Controller")
                delay(2000)
                if (isActive) {
                    runAsController(context)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("AutoRoleViewModel", "Error during Controlee attempt", e)
                _uiState.update { it.copy(role = "エラー: ${e.message}") }
            }
        }
    }

    // Controllerとして振る舞うコルーチン
    private suspend fun runAsController(context: Context) {
        try {
            _uiState.update { it.copy(role = "待機中 (Controller)") }
            android.util.Log.d("AutoRoleViewModel", "Starting as Controller")

            val uwbManager = UwbManager.createInstance(context)
            val controllerSession = uwbManager.controllerSessionScope()

            val fixedComplexChannel = UwbComplexChannel(9, 0)

            val params = UwbControllerParams(
                address = controllerSession.localAddress.address,
                channel = fixedComplexChannel.channel,
                preambleIndex = fixedComplexChannel.preambleIndex,
            )
            val encodedParams = UwbControllerParams.encode(params)

            android.util.Log.d("AutoRoleViewModel", "Starting BLE peripheral...")
            // BLEアドバタイズを開始し、Controleeからのアドレス書き込みを待つ
            val controleeAddress = BlePeripheral.startPeripheralAndWaitForAddress(
                context = context,
                onCharacteristicReadRequest = {
                    android.util.Log.d("AutoRoleViewModel", "Characteristic read requested")
                    encodedParams
                }
            )

            android.util.Log.d("AutoRoleViewModel", "Received Controlee address")
            _uiState.update { it.copy(role = "Controller") }

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

            android.util.Log.d("AutoRoleViewModel", "Starting UWB ranging as Controller")
            controllerSession.prepareSession(rangingParameters).collect(::handleRangingResult)

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _uiState.update { it.copy(role = "待機失敗") }
            android.util.Log.e("AutoRoleViewModel", "Failed in runAsController", e)
        } finally {
            BlePeripheral.stop()
        }
    }

    // Controleeとして振る舞うコルーチン
    private suspend fun runAsControlee(context: Context) {
        _uiState.update { it.copy(role = "検索中 (Controlee)") }
        android.util.Log.d("AutoRoleViewModel", "Starting as Controlee")

        val bleCentral = BleCentral(context)
        try {
            android.util.Log.d("AutoRoleViewModel", "Connecting and reading characteristic...")
            val paramsByteArray = bleCentral.connectAndReadCharacteristic()
            android.util.Log.d("AutoRoleViewModel", "Read params: ${paramsByteArray.size} bytes")

            val params = UwbControllerParams.decode(paramsByteArray)
            android.util.Log.d("AutoRoleViewModel", "Decoded params: channel=${params.channel}")

            val uwbManager = UwbManager.createInstance(context)
            val address = uwbManager.controleeSessionScope().localAddress.address
            android.util.Log.d("AutoRoleViewModel", "Writing address...")
            bleCentral.writeCharacteristic(address)

            _uiState.update { it.copy(role = "Controlee") }
            android.util.Log.d("AutoRoleViewModel", "Successfully became Controlee")

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

            android.util.Log.d("AutoRoleViewModel", "Starting UWB ranging as Controlee")
            controleeSession.prepareSession(rangingParameters).collect(::handleRangingResult)

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.e("AutoRoleViewModel", "Controlee failed: ${e.message}", e)
            if (e !is TimeoutCancellationException) {
                _uiState.update { it.copy(role = "接続失敗") }
            }
            throw e
        } finally {
            bleCentral.destroy()
        }
    }

    // 測距結果をUI Stateに反映する共通関数
    private fun handleRangingResult(rangingResult: RangingResult) {
        // UWBの測距結果をUI Stateに反映させる
        when (rangingResult) {
            is RangingResult.RangingResultPosition -> {
                // 測距に成功したら、距離や角度の情報を更新する
                _uiState.update { currentState ->
                    currentState.copy(
                        // rangingResultから取得できる値をセット
                        distance = rangingResult.position.distance?.value,
                        azimuth = rangingResult.position.azimuth?.value,
                        elevation = rangingResult.position.elevation?.value
                    )
                }
            }
            is RangingResult.RangingResultPeerDisconnected -> {
                // 相手との接続が切れたら、UIを初期状態に戻す
                // 役割は維持しつつ、距離などをリセット
                _uiState.update { UwbRoleUiState(role = it.role) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        rangingJob?.cancel()
        BlePeripheral.stop() // ViewModelが破棄される際にも念のためBLEを停止
    }
}

// 自動役割決定用のUI状態データクラス
data class UwbRoleUiState(
    val distance: Float? = null,
    val azimuth: Float? = null,
    val elevation: Float? = null,
    val role: String = "初期化中..."
)
