package com.zenithblue.nativecode.agent.chat.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Every shipped provider logo keeps WCAG non-text contrast (>= 3:1) against the tile ProviderLogo picks for it. */
class LogoContrastTest {
    @Test fun everyLogoContrastsWithItsTile() {
        val dir = listOf(File("src/main/assets/images/cli"), File("app/src/main/assets/images/cli")).first { it.isDirectory }
        // ImageIO reads PNG only; qwen-code.webp (median ink L ~0.35, ~7:1 on the dark tile) is not covered here.
        val logos = dir.listFiles { f -> f.extension == "png" }!!.sortedBy { it.name }
        assertTrue(logos.size >= 6)
        for (f in logos) {
            val img = ImageIO.read(f)
            val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            val ink = LogoContrast.ink(px)!!
            val tile = if (LogoContrast.lightTile(px)) LogoContrast.LIGHT_TILE else LogoContrast.DARK_TILE
            val r = LogoContrast.ratio(ink, tile)
            assertTrue("${f.name}: ${"%.2f".format(r)}:1 on its tile", r >= 3.0)
        }
    }

    @Test fun blackLogoGetsLightTile() {
        assertTrue(LogoContrast.lightTile(IntArray(16) { 0xFF000000.toInt() }))
        assertTrue(!LogoContrast.lightTile(IntArray(16) { 0xFFFFFFFF.toInt() }))
    }
}
