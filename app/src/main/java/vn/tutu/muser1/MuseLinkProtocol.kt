package vn.tutu.muser1

import org.json.JSONObject

/**
 * Khung message protocol cho Muse Link.
 *
 * Cấu trúc message sẽ được tinh chỉnh theo `protocols/` và
 * `linux/src/musegadget/link_client.py` của Meta Muse Gadget SDK.
 */
object MuseLinkProtocol {

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
        val params = JSONObject()
            .put("node_id", nodeId.lowercase())
            .put("display_name", displayName)
            .put("platform", "android")
            .put("version", agentVersion)
            .put("device_family", "speaker")
            .put("model_id", "phicomm-r1")
            .put("is_wakeup_supported", false)
            .put("commands_v2", commandsV2)

        if (networkSsid.isNotBlank()) {
            params.put("metadata", JSONObject().put("network_ssid", networkSsid))
        }

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
