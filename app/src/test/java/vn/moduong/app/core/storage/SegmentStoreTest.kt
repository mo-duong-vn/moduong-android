package vn.moduong.app.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentStoreTest {
    private val temporaryRoot: File = Files.createTempDirectory("segment-store-test").toFile()

    @After
    fun cleanUp() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun finalizeAtomicallyRenamesStagingFileAndPreservesItsBytes() {
        val store = SegmentStore(temporaryRoot)
        val session = store.createSession(UUID.randomUUID().toString())
        val staging = store.stagingFile(session, 1)
        val content = byteArrayOf(0, 1, 2, 3, 42)
        staging.writeBytes(content)

        val finalized = store.finalizeSegment(session, staging)

        assertFalse(staging.exists())
        assertTrue(finalized.isFile)
        assertEquals("segment-0001.mp4", finalized.name)
        assertArrayEquals(content, finalized.readBytes())
    }

    @Test
    fun emptySegmentIsRejectedWithoutCreatingFinalizedFile() {
        val store = SegmentStore(temporaryRoot)
        val session = store.createSession(UUID.randomUUID().toString())
        val staging = store.stagingFile(session, 1)
        assertTrue(staging.createNewFile())

        assertThrows(IOException::class.java) {
            store.finalizeSegment(session, staging)
        }

        assertTrue(staging.exists())
        assertFalse(File(session.directory, "segment-0001.mp4").exists())
    }

    @Test
    fun stagingFileOutsideSessionDirectoryIsRejected() {
        val store = SegmentStore(temporaryRoot)
        val session = store.createSession(UUID.randomUUID().toString())
        val outsideDirectory = File(temporaryRoot, "outside").apply { mkdirs() }
        val outsideStaging = File(outsideDirectory, "segment-0001.pending.mp4")
        outsideStaging.writeBytes(byteArrayOf(7, 8, 9))

        assertThrows(IllegalArgumentException::class.java) {
            store.finalizeSegment(session, outsideStaging)
        }

        assertTrue(outsideStaging.exists())
        assertFalse(File(session.directory, "segment-0001.mp4").exists())
    }

    @Test
    fun sessionDirectoryOutsideStoreIsRejected() {
        val store = SegmentStore(temporaryRoot)
        val sessionId = UUID.randomUUID().toString()
        val outsideDirectory = File(temporaryRoot, "outside-session").apply { mkdirs() }
        val outsideSession = RecordingSessionFiles(sessionId, outsideDirectory)

        assertThrows(IllegalArgumentException::class.java) {
            store.stagingFile(outsideSession, 1)
        }
    }
}
