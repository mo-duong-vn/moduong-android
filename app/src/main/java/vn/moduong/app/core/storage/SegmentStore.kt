package vn.moduong.app.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class RecordingSessionFiles(
    val id: String,
    val directory: File,
)

internal class SegmentStore(private val recordingsDirectory: File) {
    fun createSession(sessionId: String): RecordingSessionFiles {
        require(SESSION_ID.matches(sessionId)) { "Invalid session identifier" }
        val sessionDirectory = File(recordingsDirectory, sessionId)
        if (!sessionDirectory.mkdirs() && !sessionDirectory.isDirectory) {
            throw IOException("Could not create recording directory")
        }
        return RecordingSessionFiles(sessionId, sessionDirectory)
    }

    fun stagingFile(session: RecordingSessionFiles, segmentNumber: Int): File {
        require(segmentNumber > 0) { "Segment numbers start at one" }
        requireOwnedSession(session)
        return File(session.directory, "segment-%04d.pending.mp4".format(segmentNumber))
    }

    @Throws(IOException::class)
    fun finalizeSegment(session: RecordingSessionFiles, stagingFile: File): File {
        requireOwnedSession(session)
        require(stagingFile.parentFile?.canonicalFile == session.directory.canonicalFile) {
            "Staging file must belong to its session"
        }
        require(stagingFile.name.matches(STAGING_FILE)) { "Unexpected staging file name" }
        if (!stagingFile.isFile || stagingFile.length() == 0L) {
            throw IOException("Recording did not produce a non-empty MP4 file")
        }

        val finalized = File(session.directory, stagingFile.name.removeSuffix(".pending.mp4") + ".mp4")
        if (finalized.exists()) throw IOException("Finalized segment already exists")
        try {
            Files.move(stagingFile.toPath(), finalized.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: IOException) {
            throw IOException("Could not atomically finalize video segment", error)
        }
        return finalized
    }

    private fun requireOwnedSession(session: RecordingSessionFiles) {
        require(SESSION_ID.matches(session.id)) { "Invalid session identifier" }
        val expected = File(recordingsDirectory, session.id).canonicalFile
        require(session.directory.canonicalFile == expected) { "Session directory is outside the recording store" }
    }

    private companion object {
        val SESSION_ID = Regex("[a-f0-9-]{36}")
        val STAGING_FILE = Regex("segment-[0-9]{4}\\.pending\\.mp4")
    }
}
