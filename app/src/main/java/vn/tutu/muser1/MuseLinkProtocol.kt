package vn.tutu.muser1

import org.json.JSONObject

/**
 * Khung message protocol cho Muse Link.
 *
 * Cấu trúc message sẽ được tinh chỉnh theo `protocols/` và
 * `linux/src/musegadget/link_client.py` của Meta Muse Gadget SDK.
 */
object MuseLinkProtocol {

    const val TYPE_REGISTER = "link.register"
    const val TYPE_COMMAND = "command"
    const val TYPE_RESULT = "result"
    const val TYPE_EVENT = "event"

    fun buildRegisterMessage(deviceId: String, agentVersion: String): String {
        return JSONObject()
            .put("type", TYPE_REGISTER)
            .put("device_id", deviceId)
            .put("agent_version", agentVersion)
            .put("platform", "android")
            .put("capabilities", arrayOf("tts", "audio", "volume", "wifi_status"))
            .toString()
    }

    fun buildResult(requestId: String, ok: Boolean, result: JSONObject): String {
        return JSONObject()
            .put("type", TYPE_RESULT)
            .put("request_id", requestId)
            .put("ok", ok)
            .put("result", result)
            .toString()
    }

    fun buildEvent(name: String, payload: JSONObject): String {
        return JSONObject()
            .put("type", TYPE_EVENT)
            .put("name", name)
            .put("payload", payload)
            .toString()
    }
}
