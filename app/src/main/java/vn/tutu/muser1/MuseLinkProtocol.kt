package vn.tutu.muser1

import org.json.JSONObject

/**
 * Khung message protocol cho Muse Link.
 *
 * Cấu trúc message sẽ được tinh chỉnh theo `protocols/` và
 * `linux/src/musegadget/link_client.py` của Meta Muse Gadget SDK.
 */
object MuseLinkProtocol {

    /** Theo DEVICE.md: luôn là "gadget" với thiết bị dựng bằng SDK. */
    const val DEVICE_FAMILY = "gadget"

    /** Danh tính phần cứng ổn định của loa (định dạng [a-z0-9-]{1,64}). */
    const val MODEL_ID = "phicomm-r1"
    const val MODEL_NAME = "Phicomm R1"

    const val TYPE_REQ = "req"
    const val METHOD_REGISTER = "link.register"
    const val METHOD_INVOKE = "link.invoke"
    const val METHOD_RESULT = "link.result"

    fun buildRegisterMessage(
        requestId: String,
        nodeId: String,
        displayName: String,
        agentVersion: String,
        commandsV2: JSONObject,
        networkSsid: String = ""
    ): String {
        // Tự mô tả thiết bị theo hợp đồng protocols/DEVICE.md của Muse
        // Gadget SDK: mọi gadget đăng ký device_family "gadget" (giá trị
        // "speaker" trước đây là định dạng cũ), model_id ổn định theo phần
        // cứng và metadata.model_name là tên người dùng biết tới.
        val metadata = JSONObject()
            .put("model_name", MODEL_NAME)
        if (networkSsid.isNotBlank()) {
            metadata.put("network_ssid", networkSsid)
        }

        val params = JSONObject()
            .put("node_id", nodeId.lowercase())
            .put("display_name", displayName)
            .put("platform", "android")
            .put("version", agentVersion)
            .put("device_family", DEVICE_FAMILY)
            .put("model_id", MODEL_ID)
            .put("metadata", metadata)
            .put("is_wakeup_supported", false)
            .put("commands_v2", commandsV2)

        return JSONObject()
            .put("type", TYPE_REQ)
            .put("id", requestId)
            .put("method", METHOD_REGISTER)
            .put("params", params)
            .toString()
    }

    fun buildInvokeResult(requestId: String, outcome: JSONObject): String {
        val out = JSONObject()
            .put("method", METHOD_RESULT)
            .put("id", requestId)
            .put("ok", outcome.optBoolean("ok", false))
        if (outcome.has("payload")) {
            out.put("payload", outcome.opt("payload"))
        }
        if (outcome.has("error")) {
            out.put("error", outcome.optString("error"))
        }
        return out.toString()
    }
}
