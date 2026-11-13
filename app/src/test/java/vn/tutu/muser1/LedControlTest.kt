package vn.tutu.muser1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedControlTest {

    private fun node(
        name: String,
        files: Set<String> = setOf("brightness", "max_brightness"),
        max: Int = 255,
        writable: Boolean = true
    ) = LedNodeInfo(name, files, max, writable)

    @Test
    fun parseColorAcceptsCommonForms() {
        assertTrue(intArrayOf(255, 140, 0).contentEquals(LedLogic.parseColor("#FF8C00")!!))
        assertTrue(intArrayOf(0, 206, 209).contentEquals(LedLogic.parseColor("00CED1")!!))
        assertTrue(intArrayOf(255, 255, 255).contentEquals(LedLogic.parseColor("#FFF")!!))
        assertTrue(intArrayOf(147, 112, 219).contentEquals(LedLogic.parseColor("#9370db")!!))
    }

    @Test
    fun parseColorRejectsGarbage() {
        assertNull(LedLogic.parseColor(""))
        assertNull(LedLogic.parseColor("#12345"))
        assertNull(LedLogic.parseColor("orange"))
        assertNull(LedLogic.parseColor("#GGGGGG"))
    }

    @Test
    fun channelOfRecognizesChannelTokens() {
        assertEquals("r", LedLogic.channelOf("led_red"))
        assertEquals("g", LedLogic.channelOf("rgb-g"))
        assertEquals("b", LedLogic.channelOf("blue"))
        assertEquals("r", LedLogic.channelOf("r1_red_led"))
        assertNull(LedLogic.channelOf("mmc0"))
        assertNull(LedLogic.channelOf("r1-ring"))
    }

    @Test
    fun choosePlanPrefersRgbTriple() {
        val nodes = listOf(node("led_red"), node("led_green"), node("led_blue"), node("white"))
        val plan = LedLogic.choosePlan(nodes)
        assertEquals(LedStrategy.RGB_TRIPLE, plan.strategy)
        assertEquals("led_red", plan.channels["r"])
        assertTrue(LedLogic.isAvailable(plan, nodes))
    }

    @Test
    fun choosePlanFallsBackToSingleBrightness() {
        val nodes = listOf(node("r1_led"), node("mmc0", writable = false))
        val plan = LedLogic.choosePlan(nodes)
        assertEquals(LedStrategy.SINGLE_BRIGHTNESS, plan.strategy)
        assertEquals("r1_led", plan.channels["main"])
        assertTrue(LedLogic.isAvailable(plan, nodes))
    }

    @Test
    fun colorFileOnlyIsEvidenceNotControl() {
        val nodes = listOf(
            LedNodeInfo("ring", setOf("color", "uevent"), 255, brightnessWritable = false)
        )
        val plan = LedLogic.choosePlan(nodes)
        assertEquals(LedStrategy.COLOR_FILE, plan.strategy)
        assertFalse(LedLogic.isAvailable(plan, nodes))
        assertTrue(LedLogic.planWrites(plan, nodes, intArrayOf(255, 0, 0), 100).isEmpty())
    }

    @Test
    fun unavailableWhenBrightnessNotWritable() {
        val nodes = listOf(node("led_red", writable = false), node("led_green"), node("led_blue"))
        val plan = LedLogic.choosePlan(nodes)
        assertEquals(LedStrategy.RGB_TRIPLE, plan.strategy)
        assertFalse(LedLogic.isAvailable(plan, nodes))
    }

    @Test
    fun planWritesScalesByChannelBrightnessAndMax() {
        val nodes = listOf(
            node("led_red", max = 255), node("led_green", max = 100), node("led_blue", max = 255)
        )
        val plan = LedLogic.choosePlan(nodes)
        val writes = LedLogic.planWrites(plan, nodes, intArrayOf(255, 128, 0), 50)
        // r: 255*50% → 127 (max 255); g: 128*50% = 64 → *100/255 = 25; b: 0
        assertEquals(listOf("led_red" to 127, "led_green" to 25, "led_blue" to 0), writes)
        val off = LedLogic.planWrites(plan, nodes, intArrayOf(255, 128, 0), 0)
        assertTrue(off.all { it.second == 0 })
    }

    @Test
    fun planWritesSingleUsesBrightnessOnly() {
        val nodes = listOf(node("r1_led", max = 10))
        val plan = LedLogic.choosePlan(nodes)
        val writes = LedLogic.planWrites(plan, nodes, intArrayOf(0, 0, 255), 80)
        assertEquals(listOf("r1_led" to 8), writes)
    }
}
