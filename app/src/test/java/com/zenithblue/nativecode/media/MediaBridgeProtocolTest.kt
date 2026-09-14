package com.zenithblue.nativecode.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaBridgeProtocolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testControlFrameRoundTrip() {
        val out = ByteArrayOutputStream()
        val original = """{"v":1,"cmd":"ping","extra":42}"""

        MediaBridgeProtocol.writeControlString(out, original)
        val bytes = out.toByteArray()

        val parsed = MediaBridgeProtocol.readControlString(ByteArrayInputStream(bytes))
        assertEquals(original, parsed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testControlFrameOversizeRejected() {
        val out = ByteArrayOutputStream()
        val hugeString = "A".repeat(MediaBridgeProtocol.MAX_CONTROL_FRAME_SIZE + 1)
        MediaBridgeProtocol.writeControlString(out, hugeString)
    }

    @Test
    fun testNcmHeaderRoundTrip() {
        val out = ByteArrayOutputStream()
        val header = MediaBridgeProtocol.NcmHeader(
            magic = MediaBridgeProtocol.MAGIC_NCM1,
            version = MediaBridgeProtocol.PROTOCOL_VERSION,
            type = MediaBridgeProtocol.TYPE_PACKET,
            flags = MediaBridgeProtocol.FLAG_KEYFRAME,
            streamId = 101,
            ptsUs = 123456789L,
            payloadLength = 4096
        )

        MediaBridgeProtocol.writeNcmHeader(out, header)
        val read = MediaBridgeProtocol.readNcmHeader(ByteArrayInputStream(out.toByteArray()))

        assertEquals(MediaBridgeProtocol.MAGIC_NCM1, read.magic)
        assertEquals(MediaBridgeProtocol.PROTOCOL_VERSION, read.version)
        assertEquals(MediaBridgeProtocol.TYPE_PACKET, read.type)
        assertEquals(MediaBridgeProtocol.FLAG_KEYFRAME, read.flags)
        assertEquals(101, read.streamId)
        assertEquals(123456789L, read.ptsUs)
        assertEquals(4096, read.payloadLength)
    }

    @Test
    fun testPathAllowedValidation() {
        val root = tempFolder.newFolder("allowed_root")
        val inside = File(root, "sample.mp4").apply { createNewFile() }
        val outside = tempFolder.newFile("secret.txt")

        val allowedRoots = listOf(root)

        assertTrue(MediaBridgeProtocol.isPathAllowed(inside.absolutePath, allowedRoots))
        assertFalse(MediaBridgeProtocol.isPathAllowed(outside.absolutePath, allowedRoots))
        assertFalse(MediaBridgeProtocol.isPathAllowed(File(root, "../secret.txt").absolutePath, allowedRoots))
        assertFalse(MediaBridgeProtocol.isPathAllowed("/system/bin/sh", allowedRoots))
        assertFalse(MediaBridgeProtocol.isPathAllowed("", allowedRoots))
    }
}
