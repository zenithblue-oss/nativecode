package com.zenithblue.nativecode.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuAccelDetectorTest {
    @Test
    fun kgslIsTurnipAdreno() {
        val d = GpuAccelDetector.classify("ro.hardware=qcom", kgslPresent = true)
        assertEquals(GpuAccelDetector.MODE_TURNIP, d.mode)
        assertEquals("adreno/snapdragon", d.vendorHint)
    }

    @Test
    fun snapdragonKeywordIsTurnip() {
        val d = GpuAccelDetector.classify("soc_model=sm8650 snapdragon", kgslPresent = false)
        assertEquals(GpuAccelDetector.MODE_TURNIP, d.mode)
        assertEquals("adreno/snapdragon", d.vendorHint)
    }

    @Test
    fun maliIsVenus() {
        val d = GpuAccelDetector.classify("ro.hardware=mt6895 mali dimensity", kgslPresent = false)
        assertEquals(GpuAccelDetector.MODE_VENUS, d.mode)
        assertEquals("mali", d.vendorHint)
    }

    @Test
    fun maliWithNodeIsPanvk() {
        val d = GpuAccelDetector.classify("ro.hardware=mt6895 mali dimensity", kgslPresent = false, maliPresent = true)
        assertEquals(GpuAccelDetector.MODE_PANVK, d.mode)
        assertEquals("mali", d.vendorHint)
        assertEquals("PANVK", GpuAccelDetector.badgeLabel(d.mode))
        assertEquals("PanVK (Kbase)", GpuAccelDetector.methodLabel(d.mode))
        assertEquals("Zink over PanVK", GpuAccelDetector.methodLabel("panvk-zink"))
    }

    @Test
    fun xclipseIsVenus() {
        val d = GpuAccelDetector.classify("xclipse 940", kgslPresent = false)
        assertEquals(GpuAccelDetector.MODE_VENUS, d.mode)
        assertEquals("xclipse", d.vendorHint)
    }

    @Test
    fun unknownIsVenus() {
        val d = GpuAccelDetector.classify("unknown-board", kgslPresent = false)
        assertEquals(GpuAccelDetector.MODE_VENUS, d.mode)
        assertEquals("unknown", d.vendorHint)
    }

    @Test
    fun methodLabels() {
        assertEquals("Turnip KGSL (Zink)", GpuAccelDetector.methodLabel("turnip"))
        assertEquals("PanVK (Kbase)", GpuAccelDetector.methodLabel("panvk"))
        assertEquals("Zink over PanVK", GpuAccelDetector.methodLabel("panvk-zink"))
        assertEquals("Panfrost (Gallium Kbase)", GpuAccelDetector.methodLabel("panfrost"))
        assertEquals("Venus (VirtIO Vulkan)", GpuAccelDetector.methodLabel("venus"))
        assertEquals("Zink over Venus", GpuAccelDetector.methodLabel("zink-venus"))
        assertEquals("TURNIP", GpuAccelDetector.badgeLabel("turnip"))
        assertEquals("PANVK", GpuAccelDetector.badgeLabel("panvk"))
        assertEquals("PANVK", GpuAccelDetector.badgeLabel("panvk-zink"))
        assertEquals("PANFROST", GpuAccelDetector.badgeLabel("panfrost"))
        assertEquals("VENUS", GpuAccelDetector.badgeLabel("zink-venus"))
    }

    @Test
    fun venusArgvPrefersNoVirglWhenHelpHasIt() {
        val argv = HostServerController.venusArgv(
            "/data/data/com.zenithblue.nativecode/files/usr/bin/virgl_test_server_android",
            "/data/data/com.zenithblue.nativecode/files/usr/tmp/.virgl_test",
            "Usage: virgl_test_server [--venus] [--no-virgl] [--socket-path <path>]"
        )
        assertEquals("--venus", argv.last())
        assertEquals(true, argv.contains("--no-virgl"))
        assertEquals(true, argv.contains("--socket-path"))
    }

    @Test
    fun pulseArgvUsesTcpModule() {
        val argv = HostServerController.pulseArgv(
            "/data/data/com.zenithblue.nativecode/files/usr/bin/pulseaudio"
        )
        assertEquals(true, argv.contains("--daemonize=no"))
        assertEquals("/system/bin/linker64", argv.first())
        assertEquals(true, argv.any { it.contains("module-native-protocol-tcp") })
        assertEquals(true, argv.contains("--exit-idle-time=-1"))
    }

    @Test
    fun snapdragonIsTurnipNotVenus() {
        val d = GpuAccelDetector.classify("pineapple sm8650 adreno", kgslPresent = true)
        assertEquals(GpuAccelDetector.MODE_TURNIP, d.mode)
        assertEquals("TURNIP", GpuAccelDetector.badgeLabel(d.mode))
        assertEquals(false, d.mode == GpuAccelDetector.MODE_VENUS)
    }

    @Test
    fun hostServerBadges() {
        assertEquals("MISSING", HostServerController.classify(false, false, false))
        assertEquals("STOPPED", HostServerController.classify(true, false, false))
        assertEquals("RUNNING", HostServerController.classify(true, true, true))
    }

    @Test
    fun vulkanVersionPacking() {
        // Vulkan 1.3.0 → (1<<22)|(3<<12)|0
        assertEquals("1.3.0", HostGlInfo.formatVulkanVersion((1 shl 22) or (3 shl 12)))
        assertEquals("1.1.128", HostGlInfo.formatVulkanVersion((1 shl 22) or (1 shl 12) or 128))
    }
}
