package com.walkietalkie.ptt.desktop

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object Photos {
    private const val MAX_SIDE = 1280
    private const val JPEG_QUALITY = 0.8f

    val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

    /** Ridimensiona (lato max 1280 px) e comprime in JPEG, come fa l'app Android. */
    fun compressForSending(file: File): ByteArray? {
        val src = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        dst.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            color = Color.WHITE // le immagini trasparenti (PNG) diventano su fondo bianco
            fillRect(0, 0, w, h)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = JPEG_QUALITY
            }
            writer.write(null, IIOImage(dst, null, null), params)
            writer.dispose()
        }
        return out.toByteArray()
    }
}
