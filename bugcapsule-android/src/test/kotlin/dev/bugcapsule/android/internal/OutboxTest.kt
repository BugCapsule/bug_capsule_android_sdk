package dev.bugcapsule.android.internal

import dev.bugcapsule.android.OutboxLimits
import dev.bugcapsule.android.core.CapsuleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class OutboxTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private fun capsule(size: Int = 100) = CapsuleCodec.gzip(ByteArray(size) { (it % 7).toByte() }).let {
        // pad to roughly the requested size while staying gzip
        if (it.size >= size) it else it + ByteArray(size - it.size)
    }

    private fun outbox(limits: OutboxLimits = OutboxLimits(), dir: File = tmp.root.resolve("outbox")) =
        UploadOutbox(dir, limits, { now }, Random(1))

    private fun ids(o: UploadOutbox) = o.entries().map { it.capsuleId }

    @Test
    fun countLimitEvictsOldestFirst() {
        val o = outbox(OutboxLimits(maxCount = 3))
        for (i in 1..5) {
            now += 1000
            assertTrue(o.enqueue("c$i", capsule()))
        }
        assertEquals(listOf("c3", "c4", "c5"), ids(o))
    }

    @Test
    fun byteLimitEvictsOldestAndRejectsOversized() {
        val o = outbox(OutboxLimits(maxBytes = 1000))
        now += 1; o.enqueue("a", capsule(400))
        now += 1; o.enqueue("b", capsule(400))
        now += 1; o.enqueue("c", capsule(400))
        assertEquals(listOf("b", "c"), ids(o))
        assertFalse("larger than the whole outbox", o.enqueue("big", capsule(2000)))
    }

    @Test
    fun ageLimitDropsExpired() {
        val o = outbox(OutboxLimits(maxAgeMs = 10_000))
        o.enqueue("old", capsule())
        now += 20_000
        o.enqueue("new", capsule())
        assertEquals(listOf("new"), ids(o))
    }

    @Test
    fun tempFilesRemovedAndCorruptFilesDropped() {
        val dir = tmp.root.resolve("outbox").apply { mkdirs() }
        File(dir, "1_x.bugcapsule.123.tmp").writeText("partial")
        File(dir, "5_corrupt.bugcapsule").writeText("not gzip")
        val o = outbox(dir = dir)
        assertFalse(File(dir, "1_x.bugcapsule.123.tmp").exists())
        val uploaded = ArrayList<Int>()
        o.flush({ uploaded.add(it.size); UploadResult(UploadStatus.UPLOADED) }, { true })
        assertTrue(uploaded.isEmpty())
        assertTrue(ids(o).isEmpty())
    }

    @Test
    fun uploadsOldestFirstAndDeletesOnSuccessOrRejection() {
        val o = outbox()
        now += 1; o.enqueue("a", capsule())
        now += 1; o.enqueue("b", capsule())
        val order = ArrayList<Int>()
        var n = 0
        o.flush({ order.add(n++); if (n == 1) UploadResult(UploadStatus.UPLOADED) else UploadResult(UploadStatus.REJECTED) }, { true })
        assertEquals(listOf(0, 1), order)
        assertTrue(ids(o).isEmpty())
    }

    @Test
    fun retryBacksOffExponentiallyWithJitterAndHonoursRetryAfter() {
        val o = outbox()
        o.enqueue("a", capsule())
        val fail = { _: ByteArray -> UploadResult(UploadStatus.RETRY_LATER) }
        o.flush(fail, { true })
        val first = o.retryAtMs!! - now
        assertTrue("30 s ± 20 %: $first", first in 24_000..36_000)
        // Still backing off: not attempted, "queued".
        var attempted = false
        val r = o.flush({ attempted = true; UploadResult(UploadStatus.UPLOADED) }, { true }, onlyId = "a")
        assertFalse(attempted)
        assertEquals(UploadStatus.RETRY_LATER, r!!.status)
        now = o.retryAtMs!! + 1
        o.flush(fail, { true })
        val second = o.retryAtMs!! - now
        assertTrue("60 s ± 20 %: $second", second in 48_000..72_000)
        now = o.retryAtMs!! + 1
        o.flush({ UploadResult(UploadStatus.RETRY_LATER, retryAfterMs = 3_600_000) }, { true })
        assertTrue(o.retryAtMs!! - now >= 3_600_000 * 0.8)
        // Cap at one hour.
        repeat(10) {
            now = o.retryAtMs!! + 1
            o.flush(fail, { true })
        }
        assertTrue(o.retryAtMs!! - now <= 3_600_000 * 1.2)
        now = o.retryAtMs!! + 1
        o.flush({ UploadResult(UploadStatus.UPLOADED) }, { true })
        assertNull(o.retryAtMs)
        assertTrue(ids(o).isEmpty())
    }

    @Test
    fun notAllowedStopsUploads() {
        val o = outbox()
        o.enqueue("a", capsule())
        var calls = 0
        o.flush({ calls++; UploadResult(UploadStatus.UPLOADED) }, { false })
        assertEquals(0, calls)
        assertEquals(listOf("a"), ids(o))
    }

    @Test
    fun killSwitchPurgeDeletesEverything() {
        val o = outbox()
        o.enqueue("a", capsule()); o.enqueue("b", capsule())
        assertEquals(2, o.purge())
        assertTrue(ids(o).isEmpty())
    }

    @Test
    fun diskErrorDropsTheCapsule() {
        val dir = tmp.root.resolve("outbox")
        val o = outbox(dir = dir)
        dir.deleteRecursively()
        dir.parentFile!!.resolve("outbox").writeText("a file where the directory was")
        assertFalse(o.enqueue("a", capsule()))
    }
}
