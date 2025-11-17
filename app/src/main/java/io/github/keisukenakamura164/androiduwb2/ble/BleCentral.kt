package io.github.keisukenakamura164.androiduwb2.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission")
class BleCentral(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private var connectContinuation: Continuation<BluetoothGatt>? = null
    private var readContinuation: Continuation<ByteArray>? = null
    private var writeContinuation: Continuation<Unit>? = null

    // 統一されたGATTコールバック
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            android.util.Log.d("BleCentral", "Connection state: $newState, status: $status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                android.util.Log.d("BleCentral", "Connected, discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectContinuation?.resumeWithException(RuntimeException("Disconnected"))
                connectContinuation = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            android.util.Log.d("BleCentral", "Services discovered, status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(BleUuid.GATT_SERVICE_UUID)
                if (service != null) {
                    android.util.Log.d("BleCentral", "Target service found")
                    connectContinuation?.resume(gatt)
                    connectContinuation = null
                } else {
                    android.util.Log.e("BleCentral", "Target service not found")
                    connectContinuation?.resumeWithException(RuntimeException("Service not found"))
                    connectContinuation = null
                }
            } else {
                connectContinuation?.resumeWithException(RuntimeException("Discovery failed: $status"))
                connectContinuation = null
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            android.util.Log.d("BleCentral", "Characteristic read, status: $status, size: ${value.size}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readContinuation?.resume(value)
            } else {
                readContinuation?.resumeWithException(RuntimeException("Read failed: $status"))
            }
            readContinuation = null
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            android.util.Log.d("BleCentral", "Characteristic read (legacy), status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readContinuation?.resume(characteristic.value ?: byteArrayOf())
            } else {
                readContinuation?.resumeWithException(RuntimeException("Read failed: $status"))
            }
            readContinuation = null
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            android.util.Log.d("BleCentral", "Characteristic write, status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeContinuation?.resume(Unit)
            } else {
                writeContinuation?.resumeWithException(RuntimeException("Write failed: $status"))
            }
            writeContinuation = null
        }
    }

    private suspend fun scanDevice(): BluetoothDevice {
        return suspendCancellableCoroutine { continuation ->
            val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result ?: return
                    android.util.Log.d("BleCentral", "Device found: ${result.device.address}")

                    val scanRecord = result.scanRecord
                    if (scanRecord != null) {
                        val serviceUuids = scanRecord.serviceUuids ?: emptyList()
                        android.util.Log.d("BleCentral", "Service UUIDs: $serviceUuids")
                        if (serviceUuids.any { it == ParcelUuid(BleUuid.GATT_SERVICE_UUID) }) {
                            android.util.Log.d("BleCentral", "Target device found!")
                            bluetoothLeScanner.stopScan(this)
                            if (continuation.isActive) {
                                continuation.resume(result.device)
                            }
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    super.onScanFailed(errorCode)
                    android.util.Log.e("BleCentral", "Scan failed: $errorCode")
                    if (continuation.isActive) {
                        continuation.resumeWithException(RuntimeException("Scan failed: $errorCode"))
                    }
                }
            }

            continuation.invokeOnCancellation {
                bluetoothLeScanner.stopScan(scanCallback)
            }

            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleUuid.GATT_SERVICE_UUID))
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .build()

            android.util.Log.d("BleCentral", "Starting scan...")
            bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        }
    }

    suspend fun connectAndReadCharacteristic(): ByteArray {
        return withTimeoutOrNull(15_000L) {
            val device = scanDevice()
            android.util.Log.d("BleCentral", "Connecting to GATT...")

            // GATT接続とサービス発見
            bluetoothGatt = suspendCancellableCoroutine { continuation ->
                connectContinuation = continuation
                val gatt = device.connectGatt(context, false, gattCallback)
                if (gatt == null) {
                    continuation.resumeWithException(RuntimeException("connectGatt returned null"))
                    connectContinuation = null
                }
            }

            android.util.Log.d("BleCentral", "Connected and services discovered, reading characteristic...")
            readCharacteristic()
        } ?: throw RuntimeException("Connection timed out")
    }

    private suspend fun readCharacteristic(): ByteArray {
        return suspendCancellableCoroutine { continuation ->
            readContinuation = continuation

            val gatt = bluetoothGatt
            if (gatt == null) {
                continuation.resumeWithException(IllegalStateException("No GATT connection"))
                readContinuation = null
                return@suspendCancellableCoroutine
            }

            val service = gatt.getService(BleUuid.GATT_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BleUuid.GATT_CHARACTERISTIC_UUID)

            if (characteristic == null) {
                android.util.Log.e("BleCentral", "Characteristic not found")
                continuation.resumeWithException(NoSuchElementException("Characteristic not found"))
                readContinuation = null
                return@suspendCancellableCoroutine
            }

            android.util.Log.d("BleCentral", "Requesting read...")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.readCharacteristic(characteristic)
                    // API 33+では戻り値をチェックせず、コールバックで結果を待つ
                } else {
                    @Suppress("DEPRECATION")
                    if (!gatt.readCharacteristic(characteristic)) {
                        continuation.resumeWithException(RuntimeException("Failed to initiate read"))
                        readContinuation = null
                    }
                }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
                readContinuation = null
            }
        }
    }

    suspend fun writeCharacteristic(data: ByteArray) {
        suspendCancellableCoroutine<Unit> { continuation ->
            writeContinuation = continuation

            val gatt = bluetoothGatt
            if (gatt == null) {
                continuation.resumeWithException(IllegalStateException("No GATT connection"))
                writeContinuation = null
                return@suspendCancellableCoroutine
            }

            val service = gatt.getService(BleUuid.GATT_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BleUuid.GATT_CHARACTERISTIC_UUID)

            if (characteristic == null) {
                continuation.resumeWithException(NoSuchElementException("Characteristic not found"))
                writeContinuation = null
                return@suspendCancellableCoroutine
            }

            android.util.Log.d("BleCentral", "Writing ${data.size} bytes...")

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    // API 33+では戻り値をチェックせず、コールバックで結果を待つ
                } else {
                    characteristic.value = data
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    if (!gatt.writeCharacteristic(characteristic)) {
                        continuation.resumeWithException(RuntimeException("Failed to initiate write"))
                        writeContinuation = null
                    }
                }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
                writeContinuation = null
            }
        }
    }

    fun destroy() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectContinuation = null
        readContinuation = null
        writeContinuation = null
    }
}