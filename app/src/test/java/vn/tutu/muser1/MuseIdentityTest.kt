package vn.tutu.muser1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuseIdentityTest {

    @Test
    fun identityFollowsMuseGadgetConventions() {
        val identity = MuseIdentity.fromMac("FC:7C:02:57:41:8E")

        assertEquals("fc:7c:02:57:41:8e", identity.mac)
        assertEquals("57418e", identity.suffix)
        assertEquals("homelink-57418e", identity.nodeId)
        assertEquals("hatch-link:fc:7c:02:57:41:8e", identity.deviceId)
        assertEquals("MuseGadget-57418E", identity.bleName)

        // App Muse so sánh phần sau tiền tố của tên BLE và nodeId:
        // hai hậu tố phải luôn trùng nhau (phân biệt hoa/thường).
        assertEquals(
            identity.nodeId.removePrefix("homelink-"),
            identity.bleName.removePrefix("MuseGadget-").lowercase()
        )
    }

    @Test
    fun generatedIdentityIsLocallyAdministeredAndValid() {
        repeat(16) {
            val identity = MuseIdentity.generate()
            assertTrue(MuseIdentity.isValidMac(identity.mac))
            val firstOctet = identity.mac.substring(0, 2).toInt(16)
            // Unicast (bit 0 = 0) + locally administered (bit 1 = 1).
            assertEquals(0x02, firstOctet and 0x03)
            assertEquals(
                identity.nodeId.removePrefix("homelink-"),
                identity.bleName.removePrefix("MuseGadget-").lowercase()
            )
        }
    }
}
