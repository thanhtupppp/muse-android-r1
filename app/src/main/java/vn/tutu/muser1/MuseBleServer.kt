package vn.tutu.muser1

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.net.wifi.WifiManager
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Meta Muse Gadget BLE Peripheral & GATT Server.
 *
 * Triển khai theo chuẩn Meta Muse Gadget SDK:
 * - Service UUID: 7fdd3d1c-38ea-46cf-8b46-314ecf5f240c
 * - RX Characteristic: 4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01 (Write)
 * - TX Characteristic: d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c (Notify)
 * - Tên BLE: MuseGadget-XXXX (để app Meta Muse nhận diện)
 * - Manufacturer Data: 0xFFFF (cờ paired)
 */
class MuseBleServer(
    private val context: Context,
    private val onProvisioned: (() -> Unit)? = null
) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile
    private var isAdvertising = false
    @Volatile
    private var connectedDevice: BluetoothDevice? = null
    @Volatile
    private var clientMtu: Int = MuseBleFramer.DEFAULT_ATT_MTU
    @Volatile
    private var notificationsEnabled: Boolean = false
    @Volatile
    private var notifyLatch: CountDownLatch? = null

    private val rxAssembler = MuseBleFramer.RxAssembler()
    private var pairingProtocol: MuseBlePairingProtocol? = null
    private var bleRxExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var bleTxExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "Muse BLE Advertising đã kích hoạt thành công (Service: $SERVICE_UUID)")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Muse BLE Advertising thất bại với mã lỗi: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val stateName = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> "STATE_$newState"
            }
            Log.i(TAG, "Thiết bị BLE ${device.address} đổi trạng thái: $stateName (status=$status)")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    clientMtu = MuseBleFramer.DEFAULT_ATT_MTU
                    notificationsEnabled = false
                    rxAssembler.reset()
                    pairingProtocol?.resetSession()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                        notificationsEnabled = false
                        rxAssembler.reset()
                        pairingProtocol?.resetSession()
                        notifyLatch?.countDown()
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (connectedDevice == null || connectedDevice?.address == device.address) {
                clientMtu = mtu.coerceAtLeast(MuseBleFramer.DEFAULT_ATT_MTU)
            }
            Log.i(TAG, "BLE MTU cho ${device.address} = $mtu (hiệu lực $clientMtu)")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            if (!characteristic.uuid.toString().equals(RX_UUID, ignoreCase = true)) {
                return
            }
            val packet = value?.copyOf() ?: return
            if (packet.isEmpty()) return

            val completePayload = rxAssembler.push(packet) ?: return
            val jsonText = completePayload.toString(Charsets.UTF_8)
            Log.d(TAG, "BLE RX JSON (${jsonText.length} bytes): ${jsonText.take(160)}")

            ensureExecutors()
            bleRxExecutor.execute {
                try {
                    val proto = pairingProtocol ?: return@execute
                    proto.handleCommand(jsonText) { responseJson ->
                        enqueueNotification(device, responseJson)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi xử lý lệnh BLE Pairing v5", e)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val valBytes = if (notificationsEnabled) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, valBytes)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (descriptor.uuid.toString().equals(CCCD_UUID, ignoreCase = true)) {
                notificationsEnabled = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                Log.i(TAG, "CCCD ${device.address} notificationsEnabled=$notificationsEnabled")
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "BLE onNotificationSent status=$status cho ${device.address}")
            }
            notifyLatch?.countDown()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isAdvertising) {
            Log.d(TAG, "BLE Advertising đã đang chạy, bỏ qua yêu cầu trùng")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e(TAG, "Không tìm thấy BluetoothAdapter")
            return
        }

        if (!adapter.isEnabled) {
            Log.i(TAG, "Bluetooth đang tắt, gửi yêu cầu bật...")
            adapter.enable()
        }

        val rawMac = adapter.address?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" } ?: "FC:7C:02:57:41:8E"
        val deviceId = MusePreferences.getDeviceId(context)
        val existingSdkToken = MusePreferences.getMuseToken(context).takeIf { it.startsWith("mgst_") }
        pairingProtocol = MuseBlePairingProtocol(
            deviceId = deviceId,
            nodeId = deviceId,
            macAddress = rawMac,
            firmwareVersion = BuildConfig.VERSION_NAME,
            sdkToken = existingSdkToken,
            onWifiScan = { scanWifiNetworks() },
            onProvisionV2 = { req, sendStatus -> handleProvisionV2(req, sendStatus) }
        )
        rxAssembler.reset()

        // 1. Đặt tên Bluetooth theo chuẩn Muse Gadget để app Meta Muse nhận diện
        // Theo chuẩn Meta: "MuseGadget-" + 6 ký tự hex cuối của MAC (viết hoa)
        val mac = rawMac.replace(":", "")
        val suffix = if (mac.length >= 6) mac.takeLast(6).uppercase() else "57418E"
        val bleName = "MuseGadget-$suffix"
        try {
            if (adapter.name != bleName) {
                adapter.name = bleName
                Log.i(TAG, "Đã cập nhật tên Bluetooth thiết bị thành: $bleName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể đổi tên Bluetooth adapter", e)
        }

        // 2. Mở GATT Server và đăng ký Service Meta Muse
        try {
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
            if (gattServer != null) {
                val service = BluetoothGattService(
                    UUID.fromString(SERVICE_UUID),
                    BluetoothGattService.SERVICE_TYPE_PRIMARY
                )

                val rxChar = BluetoothGattCharacteristic(
                    UUID.fromString(RX_UUID),
                    BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )

                val txChar = BluetoothGattCharacteristic(
                    UUID.fromString(TX_UUID),
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )

                val cccd = BluetoothGattDescriptor(
                    UUID.fromString(CCCD_UUID),
                    BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
                )
                txChar.addDescriptor(cccd)

                service.addCharacteristic(rxChar)
                service.addCharacteristic(txChar)
                txCharacteristic = txChar

                gattServer?.addService(service)
                Log.i(TAG, "Đã tạo Meta Muse GATT Server với Service $SERVICE_UUID")
            } else {
                Log.e(TAG, "Không thể mở BluetoothGattServer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo GATT Server", e)
        }

        // 3. Khởi động BLE Advertising
        try {
            val leAdvertiser = adapter.bluetoothLeAdvertiser
            advertiser = leAdvertiser
            if (leAdvertiser == null) {
                Log.e(TAG, "Thiết bị không hỗ trợ BluetoothLeAdvertiser")
                return
            }

            try {
                leAdvertiser.stopAdvertising(advertiseCallback)
            } catch (_: Exception) {}

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()

            val serviceParcelUuid = ParcelUuid(UUID.fromString(SERVICE_UUID))
            
            // Meta Muse Gadget advertisement flags (ID: 0xFFFF):
            // Chỉ đặt Bit 0 (0x01 = FLAG_PAIRING_MODE):
            // Nếu đặt Bit 1 (0x02 = LINK_FLAG_BLUETOOTH_GADGET), HatchDeviceBleManager.onScanResult
            // sẽ loại bỏ thiết bị khi ở chế độ Wi-Fi link (includeBluetoothGadgets == false).
            // Với 0x01, advertisesBluetoothGadget = false nhưng hatchAdvertisedPairingFlag = true
            // và matchesDeviceName("MuseGadget-...") = true nên app Meta Muse luôn hiển thị loa.
            val flags = FLAG_PAIRING_MODE.toByte()

            val advertiseData = AdvertiseData.Builder()
                .addServiceUuid(serviceParcelUuid)
                .addManufacturerData(PAIRED_FLAG_COMPANY_ID, byteArrayOf(flags))
                .build()

            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            leAdvertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            Log.i(TAG, "Bắt đầu phát sóng Meta Muse BLE Advertising...")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi động BLE Advertising", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                Log.i(TAG, "Đã dừng Muse BLE Advertising")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi dừng BLE Advertising", e)
        }

        try {
            notifyLatch?.countDown()
            txCharacteristic = null
            connectedDevice = null
            rxAssembler.reset()
            pairingProtocol?.resetSession()
            pairingProtocol = null
            gattServer?.close()
            gattServer = null
            Log.i(TAG, "Đã đóng Muse GATT Server")
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi đóng GATT Server", e)
        }
    }

    private fun ensureExecutors() {
        if (bleRxExecutor.isShutdown) bleRxExecutor = Executors.newSingleThreadExecutor()
        if (bleTxExecutor.isShutdown) bleTxExecutor = Executors.newSingleThreadExecutor()
    }

    @SuppressLint("MissingPermission")
    private fun enqueueNotification(targetDevice: BluetoothDevice, responseJson: String) {
        val payload = responseJson.toByteArray(Charsets.UTF_8)
        if (payload.size > MuseBleFramer.MAX_MESSAGE_BYTES) {
            Log.e(TAG, "BLE TX payload quá lớn (${payload.size} bytes), bỏ qua")
            return
        }
        val chunks = MuseBleFramer.encodeChunks(payload, clientMtu)
        Log.d(TAG, "BLE TX JSON (${payload.size}B, ${chunks.size} chunks, mtu=$clientMtu): ${responseJson.take(160)}")
        ensureExecutors()
        bleTxExecutor.execute {
            val server = gattServer ?: return@execute
            val tx = txCharacteristic
                ?: server.getService(UUID.fromString(SERVICE_UUID))?.getCharacteristic(UUID.fromString(TX_UUID))
                ?: return@execute
            val dest = connectedDevice ?: targetDevice
            for ((idx, chunk) in chunks.withIndex()) {
                if (connectedDevice == null || gattServer == null) break
                val latch = CountDownLatch(1)
                notifyLatch = latch
                tx.value = chunk
                val sent = try {
                    server.notifyCharacteristicChanged(dest, tx, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Lỗi notify chunk $idx/${chunks.size}", e)
                    false
                }
                if (sent) {
                    latch.await(500, TimeUnit.MILLISECONDS)
                } else {
                    notifyLatch = null
                    Log.w(TAG, "notifyCharacteristicChanged trả về false ở chunk $idx/${chunks.size}")
                }
                if (idx + 1 < chunks.size) {
                    try {
                        Thread.sleep(40)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            notifyLatch = null
        }
    }

    @Suppress("DEPRECATION")
    private fun scanWifiNetworks(): List<MuseBlePairingProtocol.WifiEntry> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val cached = runCatching { wifiManager.scanResults }.getOrNull().orEmpty()
        val results = if (cached.isEmpty()) {
            runCatching {
                wifiManager.startScan()
                Thread.sleep(1500)
                wifiManager.scanResults
            }.getOrNull().orEmpty()
        } else {
            cached
        }
        val bySsid = linkedMapOf<String, MuseBlePairingProtocol.WifiEntry>()
        val connInfo = runCatching { wifiManager.connectionInfo }.getOrNull()
        val currentSsid = connInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        if (currentSsid != null) {
            bySsid[currentSsid] = MuseBlePairingProtocol.WifiEntry(
                ssid = currentSsid,
                rssi = connInfo.rssi,
                secure = true
            )
        }
        for (scan in results) {
            val ssid = scan.SSID?.trim().orEmpty()
            if (ssid.isEmpty()) continue
            val secure = scan.capabilities?.let {
                it.contains("WEP", ignoreCase = true) ||
                    it.contains("PSK", ignoreCase = true) ||
                    it.contains("EAP", ignoreCase = true) ||
                    it.contains("SAE", ignoreCase = true)
            } ?: true
            val prev = bySsid[ssid]
            if (prev == null || scan.level > prev.rssi) {
                bySsid[ssid] = MuseBlePairingProtocol.WifiEntry(ssid = ssid, rssi = scan.level, secure = secure)
            }
        }
        return bySsid.values.sortedByDescending { it.rssi }.take(20)
    }

    @Suppress("DEPRECATION")
    private fun handleProvisionV2(
        req: MuseBlePairingProtocol.ProvisionV2Request,
        sendStatus: (String) -> Unit
    ): Boolean {
        Log.i(TAG, "Nhận BLE provision_v2 cho SSID='${req.ssid}', noiseHost='${req.noiseHost}', apiUrlV2='${req.apiUrlV2}'")
        MusePreferences.saveBleProvisionConfig(
            context = context,
            accessToken = req.accessToken,
            refreshToken = req.refreshToken,
            apiUrl = req.apiUrl,
            apiUrlV2 = req.apiUrlV2,
            noiseHost = req.noiseHost,
            username = req.username
        )

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connInfo = runCatching { wifiManager?.connectionInfo }.getOrNull()
        val currentSsid = connInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val currentIp = connInfo?.ipAddress ?: 0
        val alreadyConnectedToTarget = currentSsid != null && currentSsid == req.ssid && currentIp != 0

        sendStatus("wifi_connecting")
        if (!alreadyConnectedToTarget) {
            when (val res = WifiProvisioner.connect(context, req.ssid, req.password)) {
                is WifiProvisioner.ProvisionResult.Success -> {
                    Log.i(TAG, "BLE Wi-Fi chuyển sang '${req.ssid}' thành công: IP ${res.ip}")
                }
                is WifiProvisioner.ProvisionResult.Failure -> {
                    Log.e(TAG, "BLE Wi-Fi thất bại (${res.code}) cho '${req.ssid}'")
                    sendStatus("error_wifi_failed")
                    return false
                }
            }
        } else {
            Log.i(TAG, "R1 đã kết nối sẵn Wi-Fi '${req.ssid}', giữ nguyên kết nối hiện tại")
        }

        sendStatus("wifi_connected")
        onProvisioned?.invoke() ?: AgentService.reloadMuseSession(context)
        return true
    }

    fun isAdvertisingActive(): Boolean = isAdvertising

    companion object {
        private const val TAG = "MuseBleServer"

        // Chuẩn Meta Muse Gadget
        const val SERVICE_UUID = "7fdd3d1c-38ea-46cf-8b46-314ecf5f240c"
        const val RX_UUID = "4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01"
        const val TX_UUID = "d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c"
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        const val PAIRED_FLAG_COMPANY_ID = 0xFFFF
        const val FLAG_PAIRING_MODE = 0x01
        const val FLAG_BLUETOOTH_GADGET = 0x02
    }
}

