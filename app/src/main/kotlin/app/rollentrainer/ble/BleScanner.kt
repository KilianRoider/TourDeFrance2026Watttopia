package app.rollentrainer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import app.rollentrainer.core.ble.Uuids
import app.rollentrainer.data.SensorRole
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

data class ScanItem(
    val address: String,
    val name: String,
    val rssi: Int,
    val services: Set<UUID>,
) {
    fun matches(role: SensorRole): Boolean = when (role) {
        SensorRole.TRAINER -> Uuids.FTMS in services
        SensorRole.HEART_RATE -> Uuids.HEART_RATE in services
        SensorRole.CADENCE -> Uuids.CSC in services || Uuids.CYCLING_POWER in services
        SensorRole.POWER -> Uuids.CYCLING_POWER in services
    }
}

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Emits every advertisement seen until the collector stops. */
    fun scan(): Flow<ScanItem> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth ist ausgeschaltet"))
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name ?: return
                val services = record?.serviceUuids.orEmpty().map(ParcelUuid::getUuid).toSet()
                trySend(ScanItem(result.device.address, name, result.rssi, services))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Suche fehlgeschlagen ($errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }
}
