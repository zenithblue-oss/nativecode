package com.zenithblue.nativecode.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecSelectionTest {

    @Test
    fun testSecureCodecFilter() {
        assertTrue(MediaCodecSelector.isSecureCodec("c2.qti.avc.decoder.secure"))
        assertTrue(MediaCodecSelector.isSecureCodec("c2.mtk.avc.decoder.secure"))
        assertTrue(MediaCodecSelector.isSecureCodec("OMX.qcom.video.decoder.avc.secure"))
        assertTrue(MediaCodecSelector.isSecureCodec("OMX.MTK.VIDEO.DECODER.AVC.secure"))
        assertTrue(MediaCodecSelector.isSecureCodec("vendor.decoder.secure.hevc"))
        assertTrue(MediaCodecSelector.isSecureCodec("c2.mtk.avc.encoder.secure"))

        assertFalse(MediaCodecSelector.isSecureCodec("c2.mtk.avc.decoder"))
        assertFalse(MediaCodecSelector.isSecureCodec("c2.qti.avc.decoder"))
        assertFalse(MediaCodecSelector.isSecureCodec("OMX.MTK.VIDEO.DECODER.AVC"))
        assertFalse(MediaCodecSelector.isSecureCodec("OMX.qcom.video.decoder.avc"))
        assertFalse(MediaCodecSelector.isSecureCodec("c2.android.avc.decoder"))
        assertFalse(MediaCodecSelector.isSecureCodec("c2.mtk.avc.encoder"))
        assertFalse(MediaCodecSelector.isSecureCodec("c2.qti.avc.encoder"))
    }
}
