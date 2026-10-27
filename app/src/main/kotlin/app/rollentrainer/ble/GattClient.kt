package app.rollentrainer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import app.rollentrainer.core.ble.Uuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * One BLE connection with a serialized operation queue (Android allows only one
 * outstanding GATT operation) and automatic reconnect while [connect] is active.
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    val address: String,
    private val scope: CoroutineScope,
    /** Called once services are discovered; enable notifications and send setup commands here. */
    private val onReady: suspend GattClient.() -> Unit,
    private val onValue: (UUID, ByteArray) -> Unit,
) {
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var wanted = false
    private var reconnectJob: Job? = null
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<ByteArray?>? = null

    fun connect() {
        wanted = true
        if (gatt != null) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        _state.value = ConnectionState.CONNECTING
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wanted = false
        reconnectJob?.cancel()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        pending?.complete(null)
        _state.value = ConnectionState.DISCONNECTED
    }

    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(service)?.getCharacteristic(characteristic)

    private suspend fun runOp(start: (BluetoothGatt) -> Boolean): ByteArray? = opLock.withLock {
        val g = gatt ?: return null
        val d = CompletableDeferred<ByteArray?>()
        pending = d
        val ok = runCatching { start(g) }.getOrDefault(false)
        if (!ok) {
            pending = null
            return null
        }
        val result = withTimeoutOrNull(OP_TIMEOUT_MS) { d.await() }
        pending = null
        result
    }

    /** Enables notifications (or indications) on a characteristic. Returns false if it is missing. */
    @Suppress("DEPRECATION")
    suspend fun subscribe(service: UUID, uuid: UUID, indicate: Boolean = false): Boolean {
        val c = characteristic(service, uuid) ?: return false
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val descriptor = c.getDescriptor(Uuids.CCCD) ?: return false
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return runOp {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                descriptor.value = value
                it.writeDescriptor(descriptor)
            }
        } != null
    }

    @Suppress("DEPRECATION")
    suspend fun write(service: UUID, uuid: UUID, value: ByteArray): Boolean {
        val c = characteristic(service, uuid) ?: return false
        return runOp {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = value
                it.writeCharacteristic(c)
            }
        } != null
    }

    suspend fun read(service: UUID, uuid: UUID): ByteArray? {
        val c = characteristic(service, uuid) ?: return null
        return runOp { it.readCharacteristic(c) }
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (wanted && gatt == null) connect()
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            } else {
                Log.i(TAG, "$address disconnected (status $status)")
                g.close()
                if (gatt === g) gatt = null
                pending?.complete(null)
                _state.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                g.disconnect()
                return
            }
            scope.launch {
                onReady(this@GattClient)
                _state.value = ConnectionState.CONNECTED
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Used before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onValue(c.uuid, value)
        }

        @Deprecated("Used before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // On Android 13+ the framework calls the overload above; avoid handling values twice.
            if (Build.VERSION.SDK_INT >= 33) return
            onValue(c.uuid, c.value ?: return)
        }
    }

    private companion object {
        const val TAG = "GattClient"
        const val OP_TIMEOUT_MS = 5000L
        const val RECONNECT_DELAY_MS = 3000L
    }
}
