package vn.moduong.app.core.camera

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException

internal data class VideoFileProfile(
    val codecMime: String,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val firstSamplePtsUs: Long,
    val lastSamplePtsUs: Long,
    val videoSamples: Long,
    val averageFramesPerSecond: Double,
    val sizeBytes: Long,
)

internal object MediaProbe {
    @Throws(IOException::class)
    fun inspectVideo(file: File): VideoFileProfile {
        if (!file.isFile || file.length() == 0L) throw IOException("Tệp MP4 trống hoặc không tồn tại")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                extractor.getTrackFormat(index).takeIf { format ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }?.let { index to it }
            } ?: throw IOException("Không tìm thấy video track trong MP4")

            val (trackIndex, format) = videoTrack
            extractor.selectTrack(trackIndex)
            var firstSampleUs: Long? = null
            var lastSampleUs = 0L
            var sampleCount = 0L
            while (extractor.sampleTime >= 0) {
                val timeUs = extractor.sampleTime
                if (firstSampleUs == null) firstSampleUs = timeUs
                lastSampleUs = timeUs
                sampleCount++
                if (!extractor.advance()) break
            }

            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                (lastSampleUs - (firstSampleUs ?: 0L)).coerceAtLeast(0L)
            }
            if (sampleCount == 0L || durationUs <= 0L || firstSampleUs == null) {
                throw IOException("MP4 chưa có khung hình video có thể đọc")
            }

            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Thiếu codec MIME")
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            return VideoFileProfile(
                codecMime = mime,
                width = width,
                height = height,
                durationUs = durationUs,
                firstSamplePtsUs = firstSampleUs,
                lastSamplePtsUs = lastSampleUs,
                videoSamples = sampleCount,
                averageFramesPerSecond = sampleCount * 1_000_000.0 / durationUs,
                sizeBytes = file.length(),
            )
        } finally {
            extractor.release()
        }
    }
}
