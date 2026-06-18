package net.kingmc.plugin.kingmcdonate.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class QrImageTest {

    private fun filled(width: Int, height: Int, argb: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, argb)
        return image
    }

    @Test
    fun `output is always 128x128 bytes even when the source differs`() {
        val bytes = QrImage.toMapBytes(filled(64, 200, Color.BLACK.rgb))
        assertEquals(QrImage.MAP_SIZE * QrImage.MAP_SIZE, bytes.size)
    }

    @Test
    fun `transparent pixels are treated as white`() {
        val transparent = QrImage.toMapBytes(filled(128, 128, 0x00000000))
        val white = QrImage.toMapBytes(filled(128, 128, Color.WHITE.rgb))
        assertEquals(white.toList(), transparent.toList())
    }

    @Test
    fun `black and white map to different colours`() {
        val black = QrImage.toMapBytes(filled(128, 128, Color.BLACK.rgb))
        val white = QrImage.toMapBytes(filled(128, 128, Color.WHITE.rgb))
        assertEquals(false, black.toList() == white.toList())
    }
}
