package vn.tutu.muser1

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Thông tin một node LED trong /sys/class/leds do probe thu thập được. */
data class LedNodeInfo(
    val name: String,
    val files: Set<String>,
    val maxBrightness: Int,
    val brightnessWritable: Boolean
) {
    val hasBrightness: Boolean get() = "brightness" in files
}

/** Chiến lược điều khiển suy ra từ các node tìm thấy. */
enum class LedStrategy { NONE, SINGLE_BRIGHTNESS, RGB_TRIPLE, COLOR_FILE }

/** Kế hoạch điều khiển: chiến lược + ánh xạ vai trò → tên node. */
data class LedPlan(
    val strategy: LedStrategy,
    /** "main" (SINGLE_BRIGHTNESS/COLOR_FILE) hoặc "r"/"g"/"b" (RGB_TRIPLE). */
    val channels: Map<String, String>
)

/**
 * Logic thuần của LED (không chạm Android/sysfs) để kiểm thử bằng unit
 * test JVM: chọn chiến lược từ danh sách node, dịch màu hex và tính giá
 * trị brightness cần ghi cho từng node.
 */
object LedLogic {

    /** "#RRGGBB" / "RRGGBB" / "#RGB" → [r, g, b] 0..255; sai định dạng → null. */
    fun parseColor(hex: String): IntArray? {
        var h = hex.trim()
        if (h.startsWith("#")) h = h.substring(1)
        if (h.length == 3) {
            h = buildString { for (c in h) { append(c); append(c) } }
        }
        if (h.length != 6) return null
        val v = h.toIntOrNull(16) ?: return null
        return intArrayOf((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }

    fun formatColor(rgb: IntArray): String =
        "#%02X%02X%02X".format(rgb[0], rgb[1], rgb[2])

    /** Node thuộc kênh màu nào theo tên ("led_red", "rgb-g", "blue"…), nếu có. */
    fun channelOf(nodeName: String): String? {
        val tokens = nodeName.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return when {
            tokens.any { it == "red" || it == "r" } -> "r"
            tokens.any { it == "green" || it == "g" } -> "g"
            tokens.any { it == "blue" || it == "b" } -> "b"
            else -> null
        }
    }

    fun choosePlan(nodes: List<LedNodeInfo>): LedPlan {
        val withBrightness = nodes.filter { it.hasBrightness }
        // Ưu tiên cao nhất: đủ ba node kênh R/G/B riêng.
        val byChannel = mutableMapOf<String, LedNodeInfo>()
        for (node in withBrightness) {
            val ch = channelOf(node.name) ?: continue
            if (ch !in byChannel) byChannel[ch] = node
        }
        if (byChannel.keys.containsAll(setOf("r", "g", "b"))) {
            return LedPlan(LedStrategy.RGB_TRIPLE, byChannel.mapValues { it.value.name })
        }
        // Kế tiếp: một node brightness bất kỳ (ưu tiên tên gợi ý LED vòng).
        val single = withBrightness.firstOrNull { node ->
            listOf("led", "light", "ring", "r1", "rgb").any { it in node.name.lowercase() }
        } ?: withBrightness.firstOrNull()
        if (single != null) {
            return LedPlan(LedStrategy.SINGLE_BRIGHTNESS, mapOf("main" to single.name))
        }
        // Node chỉ có file "color" mà không có brightness: ghi nhận làm
        // bằng chứng — định dạng ghi của file color khác nhau theo vendor
        // nên chưa coi là điều khiển được.
        val colorNode = nodes.firstOrNull { "color" in it.files }
        if (colorNode != null) {
            return LedPlan(LedStrategy.COLOR_FILE, mapOf("main" to colorNode.name))
        }
        return LedPlan(LedStrategy.NONE, emptyMap())
    }

    /**
     * Điều khiển được thật ⇔ có kế hoạch RGB/SINGLE và MỌI node kênh đều
     * ghi được file brightness. COLOR_FILE mới là bằng chứng, chưa đủ.
     */
    fun isAvailable(plan: LedPlan, nodes: List<LedNodeInfo>): Boolean {
        if (plan.strategy != LedStrategy.RGB_TRIPLE &&
            plan.strategy != LedStrategy.SINGLE_BRIGHTNESS
        ) return false
        val byName = nodes.associateBy { it.name }
        return plan.channels.values.all { byName[it]?.brightnessWritable == true }
    }

    /**
     * Các cặp (tên node, giá trị brightness cần ghi) cho một màu + độ
     * sáng %. SINGLE chỉ nhận độ sáng (không có kênh màu); brightness 0
     * nghĩa là tắt.
     */
    fun planWrites(
        plan: LedPlan,
        nodes: List<LedNodeInfo>,
        rgb: IntArray,
        brightnessPct: Int
    ): List<Pair<String, Int>> {
        val byName = nodes.associateBy { it.name }
        val pct = brightnessPct.coerceIn(0, 100)
        fun scaled(nodeName: String, channelValue: Int): Int {
            val max = (byName[nodeName]?.maxBrightness ?: 255).coerceAtLeast(1)
            return channelValue * pct / 100 * max / 255
        }
        return when (plan.strategy) {
            LedStrategy.RGB_TRIPLE -> listOf("r", "g", "b").map { ch ->
                val node = plan.channels.getValue(ch)
                node to scaled(node, rgb["rgb".indexOf(ch)])
            }
            LedStrategy.SINGLE_BRIGHTNESS -> {
                val node = plan.channels.getValue("main")
                val max = (byName[node]?.maxBrightness ?: 255).coerceAtLeast(1)
                listOf(node to (pct * max / 100))
            }
            else -> emptyList()
        }
    }
}

/**
 * Điều khiển vòng LED của R1 — đợt "dò đường" học theo API LED của
 * DomeeBox nhưng trung thực về phần cứng: khi Agent khởi động, probe
 * liệt kê /sys/class/leds và các service hệ thống liên quan, kết quả
 * vào GET /status tại `led_probe`. Chỉ khi probe thấy node brightness
 * GHI ĐƯỢC thì /led mới điều khiển thật qua sysfs; chưa thấy đường thì
 * trả LED_UNAVAILABLE kèm bằng chứng probe — không báo thành công giả.
 * Một lần chạy trên loa thật sẽ chốt được đường xuống đèn, đúng mẫu
 * Phase 0 đã dùng để chốt đường mic.
 */
object LedControl {

    private const val TAG = "MuseR1Led"
    private const val LEDS_DIR = "/sys/class/leds"

    @Volatile private var cachedProbe: JSONObject? = null
    @Volatile private var cachedNodes: List<LedNodeInfo> = emptyList()
    @Volatile private var cachedPlan: LedPlan = LedPlan(LedStrategy.NONE, emptyMap())

    @Volatile private var currentColor: IntArray = intArrayOf(0xFF, 0xFF, 0xFF)
    @Volatile private var currentBrightness: Int = 100
    @Volatile private var currentOn: Boolean = false

    /** Quét phần cứng thật, lưu kết quả vào prefs + bộ nhớ. */
    fun probe(context: Context): JSONObject {
        val nodes = scanNodes()
        val plan = LedLogic.choosePlan(nodes)
        val available = LedLogic.isAvailable(plan, nodes)
        cachedNodes = nodes
        cachedPlan = plan

        val nodesJson = JSONArray()
        for (node in nodes) {
            nodesJson.put(
                JSONObject()
                    .put("name", node.name)
                    .put("max_brightness", node.maxBrightness)
                    .put("brightness_writable", node.brightnessWritable)
                    .put("files", JSONArray(node.files.sorted().take(16)))
            )
        }
        val channelsJson = JSONObject()
        for ((role, node) in plan.channels) channelsJson.put(role, node)

        val result = JSONObject()
            .put("probed_at_ms", System.currentTimeMillis())
            .put("available", available)
            .put("strategy", plan.strategy.name.lowercase())
            .put("channels", channelsJson)
            .put("nodes", nodesJson)
            .put("vendor_services", JSONArray(listVendorServices()))
        cachedProbe = result
        MusePreferences.setLedProbe(context, result.toString())
        Log.i(TAG, "LED probe: available=$available strategy=${plan.strategy} nodes=${nodes.size}")
        return result
    }

    /** Kết quả probe gần nhất (bộ nhớ → prefs; rỗng nếu chưa từng probe). */
    fun probeJson(context: Context): JSONObject {
        cachedProbe?.let { return it }
        val raw = MusePreferences.getLedProbe(context)
        if (raw.isBlank()) return JSONObject()
        val parsed = try { JSONObject(raw) } catch (_: Exception) { return JSONObject() }
        cachedProbe = parsed
        return parsed
    }

    /** Trạng thái LED đầy đủ cho GET /led: probe + trạng thái đang áp. */
    fun statusJson(context: Context): JSONObject {
        val probe = probeJson(context)
        return JSONObject()
            .put("available", probe.optBoolean("available", false))
            .put("strategy", probe.optString("strategy", "none"))
            .put("color", LedLogic.formatColor(currentColor))
            .put("brightness", currentBrightness)
            .put("on", currentOn)
            .put("probe", probe)
    }

    /**
     * Áp màu/độ sáng/tắt qua sysfs. Ném IllegalStateException mang mã
     * lỗi (LED_UNAVAILABLE / INVALID_COLOR / LED_WRITE_FAILED:<node>)
     * để server dịch thành phản hồi lỗi.
     */
    fun apply(context: Context, colorHex: String?, brightnessPct: Int?, off: Boolean): JSONObject {
        if (cachedProbe == null) probe(context)
        val probe = cachedProbe ?: probe(context)
        if (!probe.optBoolean("available", false)) {
            throw IllegalStateException("LED_UNAVAILABLE")
        }
        if (colorHex != null) {
            currentColor = LedLogic.parseColor(colorHex)
                ?: throw IllegalStateException("INVALID_COLOR")
        }
        if (brightnessPct != null) {
            currentBrightness = brightnessPct.coerceIn(0, 100)
        }
        val targetBrightness = if (off) 0 else currentBrightness
        val writes = LedLogic.planWrites(cachedPlan, cachedNodes, currentColor, targetBrightness)
        if (writes.isEmpty()) throw IllegalStateException("LED_UNAVAILABLE")
        for ((node, value) in writes) {
            try {
                File("$LEDS_DIR/$node/brightness").writeText(value.toString())
            } catch (error: Exception) {
                Log.e(TAG, "Ghi brightness node $node lỗi", error)
                throw IllegalStateException("LED_WRITE_FAILED:$node")
            }
        }
        currentOn = targetBrightness > 0 &&
            (cachedPlan.strategy != LedStrategy.RGB_TRIPLE ||
                currentColor.any { it > 0 })
        Log.i(
            TAG,
            "LED set: color=${LedLogic.formatColor(currentColor)} brightness=$targetBrightness on=$currentOn"
        )
        return statusJson(context)
    }

    private fun scanNodes(): List<LedNodeInfo> {
        val dir = File(LEDS_DIR)
        val names = dir.list()?.sorted() ?: return emptyList()
        return names.map { name ->
            val nodeDir = File(dir, name)
            val files = nodeDir.list()?.toSet() ?: emptySet()
            val maxBrightness = File(nodeDir, "max_brightness")
                .takeIf { it.exists() }
                ?.let { runCatching { it.readText().trim().toIntOrNull() }.getOrNull() }
                ?: 255
            LedNodeInfo(
                name = name,
                files = files,
                maxBrightness = maxBrightness,
                brightnessWritable = File(nodeDir, "brightness").canWrite()
            )
        }
    }

    /** Service hệ thống có tên gợi ý LED/vendor — qua reflection vì ServiceManager là API ẩn. */
    private fun listVendorServices(): List<String> {
        return try {
            val clazz = Class.forName("android.os.ServiceManager")
            val method = clazz.getMethod("listServices")
            @Suppress("UNCHECKED_CAST")
            val services = method.invoke(null) as? Array<String> ?: return emptyList()
            services.filter { name ->
                listOf("led", "light", "phicomm", "rgb").any { it in name.lowercase() }
            }.take(20)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
