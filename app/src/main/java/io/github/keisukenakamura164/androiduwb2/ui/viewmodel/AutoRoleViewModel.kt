package io.github.keisukenakamura164.androiduwb2.ui.viewmodel

import android.content.Context
import androidx.core.uwb.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.keisukenakamura164.androiduwb2.UwbControllerParams
import io.github.keisukenakamura164.androiduwb2.ble.BleCentral
import io.github.keisukenakamura164.androiduwb2.ble.BlePeripheral
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
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
        // 既に測距中なら何もしない
        if (rangingJob?.isActive == true) return

        rangingJob = viewModelScope.launch {
            // Controller (Peripheral) としての準備
            val controllerJob = launch {
                runAsController(context)
            }

            // Controlee (Central) としての準備
            val controleeJob = launch {
                runAsControlee(context)
            }

            // どちらかのジョブがUWBセッションを開始したら、もう片方をキャンセルする
            // 例えば、Controleeとして接続に成功したら、Controllerとしての待機（アドバタイズ）は不要になる
            val winner = select<Unit> {
                controllerJob.onJoin { }
                controleeJob.onJoin { }
            }

            // 勝者が決まったら、敗者をキャンセル
            val winnerRole: String = select {
                controllerJob.onJoin { "CONTROLLER" }
                controleeJob.onJoin { "CONTROLEE" }
            }

            if (winnerRole == "CONTROLLER") {
                controleeJob.cancelAndJoin()
            } else {
                controllerJob.cancelAndJoin()
            }
        }
    }

    // Controllerとして振る舞うコルーチン
    private suspend fun runAsController(context: Context) {
        _uiState.update { it.copy(role = "待機中 (Controller)") }
        val uwbManager = UwbManager.createInstance(context)
        val controllerSession = uwbManager.controllerSessionScope()

        val params = UwbControllerParams(
            address = controllerSession.localAddress.address,
            channel = controllerSession.uwbComplexChannel.channel,
            preambleIndex = controllerSession.uwbComplexChannel.preambleIndex,
            sessionId = Random.nextInt(),
            sessionKeyInfo = Random.nextBytes(8)
        )
        val encodedParams = UwbControllerParams.encode(params)
        val controleeAddressFlow = MutableStateFlow<ByteArray?>(null)

        // BLEアドバタイズを開始（接続されるまで待機）
        BlePeripheral.startPeripheralAndAdvertising(
            context = context,
            onCharacteristicReadRequest = { encodedParams },
            onCharacteristicWriteRequest = { controleeAddressFlow.value = it }
        )
        // 上の関数はsuspendなので、ここで接続されるまで中断される

        // 接続されたら自分の役割を確定
        _uiState.update { it.copy(role = "Controller") }

        val controleeAddress = controleeAddressFlow.filterNotNull().first()

        val rangingParameters = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
            complexChannel = controllerSession.uwbComplexChannel,
            peerDevices = listOf(UwbDevice.createForAddress(controleeAddress)),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
            sessionId = params.sessionId,
            sessionKeyInfo = params.sessionKeyInfo,
            subSessionId = 0,
            subSessionKeyInfo = null
        )

        // UWB測距開始
        controllerSession.prepareSession(rangingParameters).collect(::handleRangingResult)
    }

    // Controleeとして振る舞うコルーチン
    private suspend fun runAsControlee(context: Context) {
        _uiState.update { it.copy(role = "検索中 (Controlee)") }

        // BLE Centralとして接続を試みる
        val params: UwbControllerParams
        val address: ByteArray
        withContext(Dispatchers.IO) {
            val bleCentral = BleCentral(context)
            // 接続に成功するまでリトライする
            while (true) {
                try {
                    bleCentral.connectGattServer()
                    break // 成功したらループを抜ける
                } catch (e: Exception) {
                    delay(1000) // 1秒待ってリトライ
                }
            }
            val paramsByteArray = bleCentral.readCharacteristic()
            params = UwbControllerParams.decode(paramsByteArray)

            val uwbManager = UwbManager.createInstance(context)
            address = uwbManager.controleeSessionScope().localAddress.address

            bleCentral.writeCharacteristic(address)
            bleCentral.destroy()
        }

        // 接続に成功したら自分の役割を確定
        _uiState.update { it.copy(role = "Controlee") }

        val uwbManager = UwbManager.createInstance(context)
        val controleeSession = uwbManager.controleeSessionScope()
        val rangingParameters = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
            complexChannel = UwbComplexChannel(params.channel, params.preambleIndex),
            peerDevices = listOf(UwbDevice.createForAddress(params.address)),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
            sessionId = params.sessionId,
            sessionKeyInfo = params.sessionKeyInfo,
            subSessionId = 0,
            subSessionKeyInfo = null
        )

        // UWB測距開始
        controleeSession.prepareSession(rangingParameters).collect(::handleRangingResult)
    }

    // 測距結果をUI Stateに反映する共通関数
    private fun handleRangingResult(result: RangingResult) {
        if (result is RangingResult.RangingResultPosition) {
            val pos = result.position
            _uiState.update {
                it.copy(distance = pos.distance?.value, azimuth = pos.azimuth?.value)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        rangingJob?.cancel()
    }
}

// 自動役割決定用のUI状態データクラス
data class UwbRoleUiState(
    val distance: Float? = null,
    val azimuth: Float? = null,
    val role: String = "初期化中..."
)
