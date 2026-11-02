package fi.iki.ede.IndexPaint

/** Paper dimensions live in document space, independent of either device's display pixels. */
enum class PaperSize(val aspectRatio: Float) {
    A4(210f / 297f),
    LETTER(8.5f / 11f),
}

object DrawingDocument {
    const val RASTER_WIDTH = 1536
    const val MIN_ZOOM = 0.55f
    const val MAX_ZOOM = 5f

    fun heightFor(paperSize: PaperSize): Int = (RASTER_WIDTH / paperSize.aspectRatio).toInt()
}
