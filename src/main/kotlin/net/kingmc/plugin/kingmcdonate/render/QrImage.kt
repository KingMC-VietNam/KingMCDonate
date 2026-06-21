package net.kingmc.plugin.kingmcdonate.render

import net.kingmc.plugin.kingmcdonate.util.Http
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.map.MapPalette
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Fetches a provider-hosted QR image and converts it to Minecraft map colour bytes.
 * The conversion ([toMapBytes]) is pure CPU (no Bukkit world state), so it is safe
 * off the main thread. A failed fetch/decode is logged and returns null — the order
 * still stands and the player can pay from the shown transfer details.
 */
object QrImage {

    const val MAP_SIZE = 128

    /** Download the QR PNG and convert it to 128x128 map bytes, or null on any failure. */
    fun fetchMapBytes(http: Http, url: String, logger: PluginLogger): ByteArray? = try {
        val bytes = http.getBytes(url)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        if (image == null) {
            logger.warn("Could not decode QR image from $url")
            null
        } else {
            toMapBytes(image)
        }
    } catch (e: Exception) {
        logger.warn("Failed to fetch QR image from $url: ${e.message}")
        null
    }

    /** Scale [image] to 128x128 and map each pixel to a map colour (transparent -> white). */
    @Suppress("DEPRECATION")
    fun toMapBytes(image: BufferedImage): ByteArray {
        val scaled = if (image.width == MAP_SIZE && image.height == MAP_SIZE) {
            image
        } else {
            BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB).also { target ->
                val g = target.createGraphics()
                g.drawImage(image, 0, 0, MAP_SIZE, MAP_SIZE, null)
                g.dispose()
            }
        }

        val out = ByteArray(MAP_SIZE * MAP_SIZE)
        for (y in 0 until MAP_SIZE) {
            for (x in 0 until MAP_SIZE) {
                val argb = scaled.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xFF
                var r = (argb ushr 16) and 0xFF
                var g = (argb ushr 8) and 0xFF
                var b = argb and 0xFF
                if (alpha < 128) {
                    r = 255
                    g = 255
                    b = 255
                }
                out[x + y * MAP_SIZE] = MapPalette.matchColor(r, g, b)
            }
        }
        return out
    }
}
