package io.github.keisukenakamura164.androiduwb2.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE ペリフェラル側のコード
 */
@SuppressLint("MissingPermission")
object BlePeripheral {

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeContinuation: CancellableContinuation<ByteArray>? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            android.util.Log.d("BlePeripheral", "Advertise start success")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            android.util.Log.e("BlePeripheral", "Advertise start failed: $errorCode")
            activeContinuation?.takeIf { it.isActive }?.resumeWithException(
                RuntimeException("Advertise start failed with error code: $errorCode")
            )
        }
    }

    suspend fun startPeripheralAndWaitForAddress(
        context: Context,
        onCharacteristicReadRequest: () -> ByteArray
    ): ByteArray {
        if (gattServer != null || advertiser != null) {
            stop()
        }

        return withTimeout(30_000L) {
            suspendCancellableCoroutine { continuation ->
                activeContinuation = continuation

                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

                advertiser = bluetoothManager.adapter.bluetoothLeAdvertiser
                if (advertiser == null) {
                    continuation.resumeWithException(RuntimeException("Failed to create advertiser"))
                    return@suspendCancellableCoroutine
                }

                val gattServerCallback = createGattServerCallback(continuation, onCharacteristicReadRequest)

                gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
                val service = BluetoothGattService(BleUuid.GATT_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                val characteristic = BluetoothGattCharacteristic(
                    BleUuid.GATT_CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
                service.addCharacteristic(characteristic)
                gattServer?.addService(service)

                continuation.invokeOnCancellation {
                    stop()
                }
            }
        }
    }

    private fun startAdvertising(advertiser: BluetoothLeAdvertiser, callback: AdvertiseCallback) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleUuid.GATT_SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleUuid.GATT_SERVICE_UUID))
            .addManufacturerData(0xFFFF, byteArrayOf(0x01, 0x02, 0x03, 0x04))
            .build()

        android.util.Log.d("BlePeripheral", "Starting advertise with service UUID: ${BleUuid.GATT_SERVICE_UUID}")
        advertiser.startAdvertising(settings, advertiseData, scanResponse, callback)
    }

    private fun createGattServerCallback(
        continuation: CancellableContinuation<ByteArray>?,
        onCharacteristicReadRequest: () -> ByteArray
    ) = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                android.util.Log.d("BlePeripheral", "Service added successfully")
                startAdvertising(advertiser!!, advertiseCallback)
            } else {
                continuation?.takeIf { it.isActive }?.resumeWithException(
                    RuntimeException("Failed to add service, status: $status")
                )
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic?.uuid == BleUuid.GATT_CHARACTERISTIC_UUID) {
                android.util.Log.d("BlePeripheral", "Read request received")
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, onCharacteristicReadRequest())
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (characteristic?.uuid == BleUuid.GATT_CHARACTERISTIC_UUID && value != null) {
                android.util.Log.d("BlePeripheral", "Write request received: ${value.size} bytes")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                continuation?.takeIf { it.isActive }?.resumeWith(Result.success(value))
            }
        }
    }

    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
        } catch (e: Exception) {
            // ignore
        } finally {
            gattServer = null
            advertiser = null
            activeContinuation = null
        }
    }
}