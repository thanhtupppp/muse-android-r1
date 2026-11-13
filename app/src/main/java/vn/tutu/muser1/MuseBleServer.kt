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
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Meta Muse Gadget BLE Peripheral & GATT Server.
 *
 * Triển khai theo chuẩn Meta Muse Gadget SDK:
 * - Service UUID: 7fdd3d1c-38ea-46cf-8b46-314ecf5f240c
 * - RX Characteristic: 4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01 (Write)
 * - TX Characteristic: d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c (Notify)
 * - Tên BLE: MuseGadget-XXXXXX (để app Meta Muse nhận diện; hậu tố phải
 *   trùng hậu tố nodeId "homelink-XXXXXX", xem [MuseIdentity])
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
    private var isStarting = false

    /** Tăng mỗi lần stop() để một start() đang chờ ở thread nền tự huỷ. */
    @Volatile
    private var startGeneration = 0

    @Volatile
    private var serviceAddedLatch: CountDownLatch? = null

    @Volatile
    private var serviceAddStatus: Int = BluetoothGatt.GATT_SUCCESS

    /** Lỗi khởi động gần nhất (ví dụ BLUETOOTH_NOT_ENABLED), để chẩn đoán. */
    @Volatile
    var lastStartError: String? = null
        private set

    @Volatile
    private var connectedDevice: BluetoothDevice? = null
    @Volatile
    private var clientMtu: Int = MuseBleFramer.DEFAULT_ATT_MTU
    @Volatile
    private var notificationsEnabled: Boolean = false
    @Volatile
    private var notifyLatch: CountDownLatch? = null

    /** Tên adapter trước khi bị đổi thành tên MuseGadget, để khôi phục khi stop(). */
    @Volatile
    private var previousAdapterName: String? = null

    @Volatile
    private var ourBleName: String? = null

    /** Payload TX gần nhất, để central đọc lại TX characteristic (chuẩn gốc READ | NOTIFY). */
    @Volatile
    private var lastTxPayload: ByteArray = ByteArray(0)

    private var advScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var advPolicyFuture: ScheduledFuture<*>? = null

    /** Mode/txPower advertising hiện hành, để phát lại đúng sau disconnect. */
    @Volatile
    private var advertisingMode: Int = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY

    @Volatile
    private var advertisingTxPower: Int = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH

    /** True khi chính sách cửa sổ pairing đã chủ động tắt advertising (đã pair). */
    @Volatile
    private var advertisingSuppressedByPolicy: Boolean = false

    /** Mốc kết thúc cửa sổ pairing phát nhanh hiện tại (0 = không có cửa sổ). */
    @Volatile
    private var pairingWindowUntilMs: Long = 0L

    private val rxAssembler = MuseBleFramer.RxAssembler()
    private var pairingProtocol: MuseBlePairingProtocol? = null
    private var bleRxExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var bleTxExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            lastStartError = null
            Log.i(TAG, "Muse BLE Advertising đã kích hoạt thành công (Service: $SERVICE_UUID)")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            lastStartError = "ADVERTISE_FAILED_$errorCode"
            Log.e(TAG, "Muse BLE Advertising thất bại với mã lỗi: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid.toString().equals(SERVICE_UUID, ignoreCase = true)) {
                serviceAddStatus = status
                serviceAddedLatch?.countDown()
                Log.i(TAG, "GATT onServiceAdded status=$status cho Service $SERVICE_UUID")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val stateName = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> "STATE_$newState"
            }
            Log.i(TAG, "Thiết bị BLE ${device.address} đổi trạng thái: $stateName (status=$status)")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Giao thức pairing là một phiên duy nhất: từ chối central
                    // thứ hai để nó không ghi đè MTU / notification / phiên
                    // pair của central đang được phục vụ.
                    val current = connectedDevice
                    if (current != null && current.address != device.address) {
                        Log.w(TAG, "Từ chối central thứ hai ${device.address}: đang phục vụ ${current.address}")
                        try {
                            gattServer?.cancelConnection(device)
                        } catch (e: Exception) {
                            Log.w(TAG, "Lỗi huỷ kết nối central thừa", e)
                        }
                        return
                    }
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
                        SpeakerButtonMonitor.disarm()
                        rxAssembler.reset()
                        pairingProtocol?.resetSession()
                        notifyLatch?.countDown()
                        restartAdvertisingAfterDisconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (connectedDevice?.address == device.address) {
                clientMtu = mtu.coerceAtLeast(MuseBleFramer.DEFAULT_ATT_MTU)
                Log.i(TAG, "BLE MTU cho ${device.address} = $mtu (hiệu lực $clientMtu)")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (connectedDevice?.address != device.address) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }
            // TX ở bản gốc là READ | NOTIFY và read trả payload gần nhất.
            val payload = if (characteristic.uuid.toString().equals(TX_UUID, ignoreCase = true)) {
                lastTxPayload
            } else {
                ByteArray(0)
            }
            val value = when {
                offset <= 0 -> payload
                offset < payload.size -> payload.copyOfRange(offset, payload.size)
                else -> ByteArray(0)
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
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
            // Chỉ central đang được phục vụ mới được ghi vào RX / lái phiên pair.
            if (connectedDevice?.address != device.address) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                Log.w(TAG, "Bỏ qua write từ thiết bị không phải central hiện tại: ${device.address}")
                return
            }
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
            if (connectedDevice?.address != device.address) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }
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
            if (connectedDevice?.address != device.address) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (descriptor.uuid.toString().equals(CCCD_UUID, ignoreCase = true)) {
                notificationsEnabled = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                Log.i(TAG, "CCCD ${device.address} notificationsEnabled=$notificationsEnabled")
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (connectedDevice?.address != device.address) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "BLE onNotificationSent status=$status cho ${device.address}")
            }
            notifyLatch?.countDown()
        }
    }

    /**
     * Khởi động BLE peripheral. Hàm này chỉ lên lịch và trả về ngay: việc chờ
     * Bluetooth bật và chờ GATT service được đăng ký xong chạy ở thread nền,
     * vì start() được gọi từ AgentService.onStartCommand (main thread).
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isStarting || gattServer != null) {
            // Server đã chạy thì start() là no-op — kể cả khi advertising
            // đang TẮT. Sau cửa sổ pairing, loa đã pair tắt advertising có
            // chủ đích (advertisingSuppressedByPolicy) nhưng GATT server vẫn
            // sống; bản cũ chỉ chặn theo isAdvertising nên mọi intent về sau
            // của AgentService (kể cả intent không liên quan như probe voice)
            // đều dựng lại toàn bộ: đóng GATT server đang có central, tạo
            // lại pairing protocol (xoá phiên pair đang dở) và phát nhanh
            // lại từ đầu — tự phá chính sách "đã pair thì im lặng".
            // Advertising thuộc quyền của chính sách cửa sổ pairing và
            // reopenPairingWindow(); các đường lỗi thật đều đã null
            // gattServer (closeGattServer) nên start() vẫn thử lại được.
            Log.d(TAG, "BLE server đã chạy (advertising=$isAdvertising), bỏ qua start() trùng")
            return
        }
        isStarting = true
        val generation = startGeneration
        Thread({
            try {
                startInternal(generation)
            } catch (e: Exception) {
                lastStartError = "START_EXCEPTION:${e.javaClass.simpleName}"
                Log.e(TAG, "Lỗi khởi động Muse BLE Server", e)
            } finally {
                if (generation == startGeneration) {
                    isStarting = false
                }
            }
        }, "MuseBleServerStart").start()
    }

    @SuppressLint("MissingPermission")
    private fun startInternal(generation: Int) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            lastStartError = "NO_BLUETOOTH_ADAPTER"
            Log.e(TAG, "Không tìm thấy BluetoothAdapter")
            return
        }

        // 0a. Chờ Bluetooth bật hẳn rồi mới lấy advertiser / mở GATT.
        // Trước đây code gọi adapter.enable() rồi đi tiếp ngay, nên khi loa
        // vừa boot mà Bluetooth chưa kịp bật, bluetoothLeAdvertiser trả về
        // null (theo tài liệu Android) và advertising chết lặng. Server SPP
        // đã chờ tối đa 15 giây; ở đây làm tương tự nhưng ở thread nền.
        if (!adapter.isEnabled) {
            Log.i(TAG, "Bluetooth đang tắt, gửi yêu cầu bật và chờ...")
            @Suppress("DEPRECATION")
            adapter.enable()
            val deadline = System.currentTimeMillis() + BLUETOOTH_ENABLE_TIMEOUT_MS
            while (!adapter.isEnabled && System.currentTimeMillis() < deadline) {
                if (generation != startGeneration) return
                try {
                    Thread.sleep(BLUETOOTH_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        if (generation != startGeneration) return
        if (!adapter.isEnabled) {
            lastStartError = "BLUETOOTH_NOT_ENABLED"
            Log.e(TAG, "Bluetooth vẫn chưa bật sau ${BLUETOOTH_ENABLE_TIMEOUT_MS}ms, huỷ khởi động BLE")
            return
        }

        // 0b. Kiểm tra khả năng peripheral TRƯỚC khi mở GATT server, để lỗi
        // thì không để lại GATT server mồ côi như trước. Lưu ý:
        // isMultipleAdvertisementSupported=false chưa chắc chắn thiết bị
        // không phát được một advertisement duy nhất, nên chỉ cảnh báo;
        // bằng chứng quyết định là advertiser null hoặc
        // AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED.
        val multiAdvSupported = try {
            adapter.isMultipleAdvertisementSupported
        } catch (e: Exception) {
            Log.w(TAG, "Không đọc được isMultipleAdvertisementSupported", e)
            false
        }
        if (!multiAdvSupported) {
            Log.w(
                TAG,
                "isMultipleAdvertisementSupported=false: chipset/stack có thể không hỗ trợ " +
                    "BLE peripheral advertising; vẫn thử lấy advertiser"
            )
        }
        val leAdvertiser = try {
            adapter.bluetoothLeAdvertiser
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi lấy BluetoothLeAdvertiser", e)
            null
        }
        if (leAdvertiser == null) {
            lastStartError = "BLE_ADVERTISER_UNAVAILABLE"
            Log.e(TAG, "Thiết bị không hỗ trợ BluetoothLeAdvertiser (peripheral mode)")
            return
        }
        advertiser = leAdvertiser
        lastStartError = null

        // 1. Danh tính Muse Gadget: nodeId / deviceId / tên BLE đều suy ra
        // từ MỘT identity lưu bền (xem MuseIdentity) — không còn lấy từ
        // ANDROID_ID, địa chỉ Bluetooth phần cứng hay MAC hardcode, nên hậu
        // tố tên BLE luôn trùng hậu tố nodeId như app Muse mong đợi.
        val identity = MusePreferences.getMuseIdentity(context)
        val existingSdkToken = MusePreferences.getMuseToken(context).takeIf { it.startsWith("mgst_") }
        pairingProtocol = MuseBlePairingProtocol(
            deviceId = identity.deviceId,
            nodeId = identity.nodeId,
            macAddress = identity.mac,
            firmwareVersion = BuildConfig.VERSION_NAME,
            sdkToken = existingSdkToken,
            onWifiScan = { scanWifiNetworks() },
            onProvisionV2 = { req, sendStatus -> handleProvisionV2(req, sendStatus) },
            onConfirmRequired = {
                // confirm_required vừa được gửi: arm nút tròn trên loa và
                // hẹn giờ huỷ phiên sau 60 giây nếu không ai bấm.
                SpeakerButtonMonitor.arm()
                scheduleConfirmTimeout(generation)
            }
        )
        SpeakerButtonMonitor.onArmedPress = {
            val device = connectedDevice
            val proto = pairingProtocol
            if (device != null && proto != null) {
                ensureExecutors()
                bleRxExecutor.execute {
                    if (proto.confirmActiveSession { json -> enqueueNotification(device, json) }) {
                        SpeakerButtonMonitor.disarm()
                        Log.i(TAG, "Đã xác nhận pair bằng nút tròn trên loa")
                    }
                }
            }
        }
        rxAssembler.reset()

        try {
            // Tên adapter là tài nguyên dùng chung với Bluetooth Classic/SPP:
            // lưu tên gốc để stop() khôi phục, nếu không loa sẽ mang tên
            // MuseGadget-... vĩnh viễn và các luồng tìm loa theo tên cũ
            // (ví dụ SPP) không còn thấy nó nữa.
            ourBleName = identity.bleName
            val currentName = adapter.name
            if (currentName != identity.bleName) {
                if (previousAdapterName == null) {
                    previousAdapterName = currentName
                }
                adapter.name = identity.bleName
                Log.i(TAG, "Đã cập nhật tên Bluetooth thiết bị thành: ${identity.bleName}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể đổi tên Bluetooth adapter", e)
        }

        // 2. Mở GATT Server, đăng ký Service Meta Muse và CHỜ onServiceAdded
        // rồi mới advertise. addService() là bất đồng bộ: advertise ngay như
        // trước khiến central có thể kết nối khi service chưa có trong
        // GATT database và discover ra tập rỗng.
        closeGattServer()
        val server = try {
            bluetoothManager?.openGattServer(context, gattServerCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi mở BluetoothGattServer", e)
            null
        }
        if (server == null) {
            lastStartError = "GATT_SERVER_UNAVAILABLE"
            Log.e(TAG, "Không thể mở BluetoothGattServer")
            return
        }
        gattServer = server

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
            // Bản gốc là READ | NOTIFY; trước đây thiếu PROPERTY_READ nên
            // central đọc TX luôn thất bại dù đã khai PERMISSION_READ.
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
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

        val latch = CountDownLatch(1)
        serviceAddedLatch = latch
        serviceAddStatus = BluetoothGatt.GATT_FAILURE
        try {
            server.addService(service)
        } catch (e: Exception) {
            lastStartError = "GATT_ADD_SERVICE_EXCEPTION"
            Log.e(TAG, "Lỗi addService cho GATT Server", e)
            closeGattServer()
            return
        }
        val serviceAdded = try {
            latch.await(SERVICE_ADD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (generation != startGeneration) return
        if (!serviceAdded || serviceAddStatus != BluetoothGatt.GATT_SUCCESS) {
            lastStartError = "GATT_SERVICE_ADD_FAILED:$serviceAddStatus"
            Log.e(TAG, "GATT service chưa sẵn sàng (added=$serviceAdded, status=$serviceAddStatus), huỷ khởi động BLE")
            closeGattServer()
            return
        }
        Log.i(TAG, "Đã tạo Meta Muse GATT Server với Service $SERVICE_UUID")

        // 3. Khởi động BLE Advertising trong cửa sổ pairing, rồi áp chính
        // sách ở scheduleAdvertisingPolicy(): phát nhanh chỉ có ý nghĩa khi
        // đang chờ pair, phát LOW_LATENCY + HIGH mãi sẽ tranh radio với
        // Wi-Fi (R1 dùng chung radio) và tốn điện vô ích sau khi đã pair.
        try {
            startAdvertisingInternal(
                leAdvertiser,
                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
            )
            pairingWindowUntilMs = System.currentTimeMillis() + PAIRING_WINDOW_MS
            scheduleAdvertisingPolicy(generation)
        } catch (e: Exception) {
            lastStartError = "ADVERTISE_EXCEPTION:${e.javaClass.simpleName}"
            Log.e(TAG, "Lỗi khởi động BLE Advertising", e)
            closeGattServer()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingInternal(leAdvertiser: BluetoothLeAdvertiser, mode: Int, txPower: Int) {
        advertisingMode = mode
        advertisingTxPower = txPower
        advertisingSuppressedByPolicy = false
        // Một lần phát mới bắt đầu: xoá lỗi của lần phát trước để /status và
        // endpoint mở pairing không đọc nhầm lỗi cũ trong lúc callback mới
        // chưa về.
        lastStartError = null
        try {
            leAdvertiser.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(txPower)
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
        Log.i(TAG, "Bắt đầu phát sóng Meta Muse BLE Advertising (mode=$mode, txPower=$txPower)...")
    }

    /**
     * Nhiều chipset Android dừng connectable advertising trong lúc có kết
     * nối và không tự phát lại sau khi central ngắt — nếu không phát lại ở
     * đây, loa sẽ biến mất khỏi mọi lần quét cho tới khi Agent restart.
     * Chỉ phát lại khi chính sách cửa sổ pairing chưa chủ động tắt.
     */
    @SuppressLint("MissingPermission")
    private fun restartAdvertisingAfterDisconnect() {
        if (gattServer == null) return
        if (advertisingSuppressedByPolicy) {
            Log.i(TAG, "Không phát lại advertising sau disconnect: chính sách pairing đã tắt")
            return
        }
        val adv = advertiser ?: return
        try {
            startAdvertisingInternal(adv, advertisingMode, advertisingTxPower)
            Log.i(TAG, "Đã phát lại BLE advertising sau khi central ngắt kết nối")
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi phát lại BLE advertising sau disconnect", e)
        }
    }

    /**
     * Mở lại cửa sổ pairing (phát nhanh 10 phút) mà không cần restart Agent —
     * gọi qua broadcast ADB vn.tutu.muser1.REOPEN_PAIRING_WINDOW khi app Muse
     * không còn thấy loa vì cửa sổ cũ đã đóng.
     */
    @SuppressLint("MissingPermission")
    fun reopenPairingWindow() {
        if (gattServer == null) {
            Log.i(TAG, "BLE server chưa chạy: start() để mở cửa sổ pairing")
            start()
            return
        }
        val adv = advertiser
        if (adv == null) {
            Log.w(TAG, "Không có advertiser để mở lại cửa sổ pairing, thử start() lại")
            start()
            return
        }
        try {
            startAdvertisingInternal(
                adv,
                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
            )
            pairingWindowUntilMs = System.currentTimeMillis() + PAIRING_WINDOW_MS
            scheduleAdvertisingPolicy(startGeneration)
            Log.i(TAG, "Đã mở lại cửa sổ pairing BLE (phát nhanh ${PAIRING_WINDOW_MS / 60_000} phút)")
        } catch (e: Exception) {
            lastStartError = "REOPEN_WINDOW_EXCEPTION:${e.javaClass.simpleName}"
            Log.e(TAG, "Lỗi mở lại cửa sổ pairing BLE", e)
        }
    }

    /** Trạng thái BLE server để LocalProvisionServer phơi qua GET /status. */
    fun statusJson(): JSONObject {
        val identity = try {
            MusePreferences.getMuseIdentity(context)
        } catch (e: Exception) {
            null
        }
        val pairingWindowRemainingMs =
            (pairingWindowUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
        return JSONObject()
            .put("running", gattServer != null)
            .put("advertising", isAdvertising)
            .put("starting", isStarting)
            .put("pairing_window_active", pairingWindowRemainingMs > 0L)
            .put("pairing_window_remaining_sec", pairingWindowRemainingMs / 1000L)
            .put("last_start_error", lastStartError ?: "")
            .put("advertising_suppressed_by_policy", advertisingSuppressedByPolicy)
            .put("central_connected", connectedDevice != null)
            .put("confirm_press_armed", SpeakerButtonMonitor.isArmed())
            .put("ble_name", identity?.bleName ?: "")
            .put("node_id", identity?.nodeId ?: "")
            .put("device_id", identity?.deviceId ?: "")
    }

    private fun ensureAdvScheduler(): ScheduledExecutorService {
        if (advScheduler.isShutdown) {
            advScheduler = Executors.newSingleThreadScheduledExecutor()
        }
        return advScheduler
    }

    /**
     * Sau khi arm nút xác nhận pair: quá 60 giây mà chưa bấm thì báo lỗi,
     * huỷ phiên pair và ngắt central — giống confirm timeout của bản ESP32.
     */
    private fun scheduleConfirmTimeout(generation: Int) {
        ensureAdvScheduler().schedule({
            if (generation != startGeneration) return@schedule
            val device = connectedDevice ?: return@schedule
            val proto = pairingProtocol ?: return@schedule
            ensureExecutors()
            bleRxExecutor.execute {
                if (proto.expireConfirmIfNeeded { json -> enqueueNotification(device, json) }) {
                    SpeakerButtonMonitor.disarm()
                    Log.w(TAG, "Hết 60s chờ bấm nút xác nhận pair: huỷ phiên và ngắt central")
                    try {
                        gattServer?.cancelConnection(device)
                    } catch (e: Exception) {
                        Log.w(TAG, "Lỗi ngắt central sau confirm timeout", e)
                    }
                }
            }
        }, MuseBlePairingProtocol.CONFIRM_PRESS_TIMEOUT_MS + CONFIRM_TIMEOUT_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    /** Hẹn giờ áp chính sách advertising sau cửa sổ pairing. */
    private fun scheduleAdvertisingPolicy(generation: Int) {
        advPolicyFuture?.cancel(false)
        advPolicyFuture = ensureAdvScheduler().schedule({
            applyAdvertisingPolicy(generation)
        }, PAIRING_WINDOW_MS, TimeUnit.MILLISECONDS)
    }

    @SuppressLint("MissingPermission")
    private fun applyAdvertisingPolicy(generation: Int) {
        if (generation != startGeneration || !isAdvertising) return
        if (connectedDevice != null) {
            // Đang có central dùng dở phiên pair: hoãn quyết định và giữ
            // trạng thái cửa sổ pairing là đang mở cho tới lần kiểm tra sau.
            pairingWindowUntilMs = System.currentTimeMillis() + POLICY_RECHECK_MS
            advPolicyFuture = ensureAdvScheduler().schedule({
                applyAdvertisingPolicy(generation)
            }, POLICY_RECHECK_MS, TimeUnit.MILLISECONDS)
            return
        }
        pairingWindowUntilMs = 0L
        val adv = advertiser ?: return
        val paired = try {
            MusePreferences.isMusePaired(context)
        } catch (e: Exception) {
            Log.w(TAG, "Không đọc được trạng thái Muse paired", e)
            false
        }
        try {
            adv.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi dừng advertising khi áp chính sách", e)
        }
        isAdvertising = false
        if (paired) {
            // Giống bản ESP32 gốc: setup xong thì tắt advertising setup.
            // GATT server vẫn mở; muốn pair lại thì mở cửa sổ pairing mới
            // bằng reopenPairingWindow() (broadcast ADB) hoặc restart Agent.
            advertisingSuppressedByPolicy = true
            Log.i(TAG, "Hết cửa sổ pairing và thiết bị đã pair: dừng BLE advertising")
        } else {
            Log.i(TAG, "Hết cửa sổ pairing, thiết bị chưa pair: hạ advertising xuống LOW_POWER")
            startAdvertisingInternal(
                adv,
                AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
                AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
            )
        }
    }

    /** Đóng GATT server hiện tại (nếu có) và xoá characteristic đang giữ. */
    private fun closeGattServer() {
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi đóng GATT Server", e)
        }
        gattServer = null
        txCharacteristic = null
        serviceAddedLatch?.countDown()
        serviceAddedLatch = null
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        // Huỷ mọi start() đang chờ ở thread nền và đánh thức latch của nó.
        startGeneration++
        isStarting = false
        serviceAddedLatch?.countDown()
        advPolicyFuture?.cancel(false)
        advPolicyFuture = null
        pairingWindowUntilMs = 0L
        SpeakerButtonMonitor.disarm()
        SpeakerButtonMonitor.onArmedPress = null

        try {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                Log.i(TAG, "Đã dừng Muse BLE Advertising")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi dừng BLE Advertising", e)
        }

        // Khôi phục tên adapter gốc để các luồng Classic/SPP tìm loa theo
        // tên cũ hoạt động lại như trước khi BLE đổi tên.
        try {
            val prev = previousAdapterName
            val ours = ourBleName
            if (prev != null && ours != null && bluetoothAdapter?.name == ours) {
                bluetoothAdapter?.name = prev
                Log.i(TAG, "Đã khôi phục tên Bluetooth adapter: $prev")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khôi phục tên Bluetooth adapter", e)
        }
        previousAdapterName = null
        ourBleName = null

        try {
            notifyLatch?.countDown()
            connectedDevice = null
            rxAssembler.reset()
            pairingProtocol?.resetSession()
            pairingProtocol = null
            closeGattServer()
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
        lastTxPayload = payload
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

    private fun scanWifiNetworks(): List<MuseBlePairingProtocol.WifiEntry> {
        // Logic quét dùng chung với đường HTTP :8080 (SpeakerWifi) để loa
        // là nguồn quét duy nhất: gộp theo SSID, phân loại bảo mật nhất
        // quán. BLE chỉ cần thêm cờ secure (mạng không phải OPEN).
        return SpeakerWifi.scan(context).map { entry ->
            MuseBlePairingProtocol.WifiEntry(
                ssid = entry.ssid,
                rssi = entry.rssi,
                secure = entry.security != "OPEN"
            )
        }
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

        private const val BLUETOOTH_ENABLE_TIMEOUT_MS = 15_000L
        private const val BLUETOOTH_POLL_INTERVAL_MS = 250L
        private const val SERVICE_ADD_TIMEOUT_MS = 3_000L

        /** Cửa sổ pairing phát nhanh, cùng cỡ cửa sổ pair của SDK gốc. */
        private const val PAIRING_WINDOW_MS = 10 * 60_000L
        private const val POLICY_RECHECK_MS = 60_000L

        /** Cộng thêm cho hạn bấm nút để message hết giờ kịp đi trước khi ngắt. */
        private const val CONFIRM_TIMEOUT_GRACE_MS = 2_000L

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

