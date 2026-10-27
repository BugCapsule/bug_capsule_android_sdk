package dev.bugcapsule.android.monitoring

import dev.bugcapsule.android.core.J
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ExitReasonsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val inApp = InAppRules(listOf("com.shop"), emptyList())

    private fun exit(pid: Int, reason: Int, at: Long, process: String? = "com.shop", importance: Int = 100, trace: ByteArray? = null) =
        ExitRecord(pid, reason, at, importance, "desc $pid", process) { trace?.inputStream() }

    @Test
    fun theFirstLaunchOnlyRemembersThenOnlyNewerMainProcessExitsCount() {
        val state = File(tmp.root, "exit_info.json")
        var exits = listOf(exit(1, ExitRecord.REASON_ANR, 1_000))
        val reasons = ExitReasons(state, "com.shop") { exits }
        assertTrue("history before the SDK is not reported", reasons.takeNew().isEmpty())
        exits = listOf(
            exit(3, ExitRecord.REASON_CRASH_NATIVE, 3_000),
            exit(4, ExitRecord.REASON_ANR, 4_000, process = "com.shop:remote"),
            exit(2, ExitRecord.REASON_LOW_MEMORY, 2_000),
            exit(1, ExitRecord.REASON_ANR, 1_000),
        )
        assertEquals(listOf(2, 3), reasons.takeNew().map { it.pid })
        assertEquals("not handled until its event is queued", listOf(2, 3), reasons.takeNew().map { it.pid })
        reasons.markHandled(2_000)
        assertEquals(listOf(3), reasons.takeNew().map { it.pid })
        reasons.markHandled(3_000)
        reasons.markHandled(1_000) // never goes back
        assertTrue("each exit once", reasons.takeNew().isEmpty())
    }

    @Test
    fun onAFreshInstallTheFirstAnrCounts() {
        val state = File(tmp.root, "exit_info.json")
        var exits = emptyList<ExitRecord>()
        val reasons = ExitReasons(state, "com.shop") { exits }
        assertTrue(reasons.takeNew().isEmpty())
        exits = listOf(exit(7, ExitRecord.REASON_ANR, 5_000))
        assertEquals(listOf(7), reasons.takeNew().map { it.pid })
    }

    @Test
    fun anAnrTheWatchdogReportedIsNotReportedAgainFromItsExit() {
        val state = File(tmp.root, "exit_info.json").apply { writeText("{\"lastTimestamp\":1}") }
        val watchdog = File(tmp.root, "watchdog_anr.json").apply { writeText("{\"pid\":7}") }
        val exits = listOf(exit(7, ExitRecord.REASON_ANR, 5_000), exit(8, ExitRecord.REASON_ANR, 6_000))
        val previous = PreviousProcess.collect(
            NativeCrashes(File(tmp.root, "native")), ExitReasons(state, "com.shop") { exits }, File(tmp.root, "scope.json"), watchdog,
        )
        val reported = ArrayList<PreviousProcess.Crash>()
        previous.report("com.shop", inApp) { reported.add(it) }
        assertEquals(1, reported.size)
        assertTrue("both handled", ExitReasons(state, "com.shop") { exits }.takeNew().isEmpty())
    }

    @Test
    fun longThreadListsAreCutToFitTheServerLimit() {
        val longPath = "/data/app/~~abcdefghijklmnop==/com.shop-qrstuvwxyz==/oat/arm64/base.odex"
        fun frames(n: Int) = (0 until n).map { linkedMapOf<String, Any?>("function" to "f$it", "package" to longPath, "instructionAddr" to "0x7a0000$it") }
        val threads = (0 until 150).map { i ->
            linkedMapOf<String, Any?>("id" to i, "name" to "t$i", "crashed" to (i == 0), "stacktrace" to linkedMapOf("frames" to frames(300)))
        }
        val fit = ThreadDump.fit(threads)
        assertTrue(fit.size <= ThreadDump.MAX_THREADS)
        assertTrue(dev.bugcapsule.android.core.Json.stringify(fit).toByteArray().size <= ThreadDump.MAX_THREADS_BYTES)
        val crashedFrames = J.list(J.obj(fit[0]["stacktrace"])!!["frames"])!!
        assertEquals(ExceptionMapper.MAX_FRAMES, crashedFrames.size)
        assertEquals("the newest frames stay", "f299", J.obj(crashedFrames.last())!!["function"])
        assertEquals(ThreadDump.MAX_FRAMES_PER_THREAD, J.list(J.obj(fit[1]["stacktrace"])!!["frames"])!!.size)
    }

    @Test
    fun onlyNativeCrashesAnrsAndForegroundLowMemoryKillsAreReported() {
        assertTrue(ExitReasons.reportable(exit(1, ExitRecord.REASON_ANR, 1, importance = 400)))
        assertTrue(ExitReasons.reportable(exit(1, ExitRecord.REASON_CRASH_NATIVE, 1)))
        assertTrue(ExitReasons.reportable(exit(1, ExitRecord.REASON_LOW_MEMORY, 1, importance = 100)))
        assertFalse("background LMK kills are normal", ExitReasons.reportable(exit(1, ExitRecord.REASON_LOW_MEMORY, 1, importance = 400)))
        assertFalse("JVM crashes are reported when they happen", ExitReasons.reportable(exit(1, ExitRecord.REASON_CRASH, 1)))
    }

    @Test
    fun anrDumpsBecomeEveryThreadWithTheMainThreadAsTheException() {
        val ev = linkedMapOf<String, Any?>()
        ExitReasons.fillAnr(ev, exit(9, ExitRecord.REASON_ANR, 1, trace = ANR_TRACE.toByteArray()), inApp)
        val value = J.obj(J.list(J.obj(ev["exception"])!!["values"])!!.single())!!
        assertEquals("ApplicationNotResponding", value["type"])
        assertEquals("desc 9", value["value"])
        val mechanism = J.obj(value["mechanism"])!!
        assertEquals("anr", mechanism["type"])
        assertEquals(false, mechanism["handled"])
        assertEquals("exit_info", J.obj(mechanism["data"])!!["source"])
        val frames = J.list(J.obj(value["stacktrace"])!!["frames"])!!.map { J.obj(it)!! }
        // Oldest call first, the blocking (native) call last.
        assertEquals("main", frames.first()["function"])
        assertEquals("__epoll_pwait", frames.last()["function"])
        assertEquals("sleep", frames[frames.size - 2]["function"])
        val onClick = frames.single { it["function"] == "onClick" }
        assertEquals("com.shop.CheckoutActivity", onClick["module"])
        assertEquals("CheckoutActivity.kt", onClick["filename"])
        assertEquals(42, onClick["lineno"])
        assertEquals(true, onClick["inApp"])
        assertEquals(true, frames.single { it["function"] == "__epoll_pwait" }["native"])

        val threads = J.list(ev["threads"])!!.map { J.obj(it)!! }
        assertEquals(listOf("main", "Signal Catcher", "binder:12345_1", "OkHttp Dispatcher"), threads.map { it["name"] })
        assertEquals(true, threads[0]["crashed"])
        assertEquals(12345L, threads[0]["id"])
        assertEquals("not attached threads keep their sysTid", 12352L, threads[2]["id"])
        assertEquals(false, threads[3]["crashed"])
        assertEquals("Blocked", threads[3]["state"])
        val catcher = J.list(J.obj(threads[1]["stacktrace"])!!["frames"])!!.map { J.obj(it)!! }
        assertEquals("an (offset …) suffix is not the function", "Java_com_shop_Native_run", catcher.first()["function"])
    }

    @Test
    fun anAnrWithoutADumpStillReportsTheKill() {
        val ev = linkedMapOf<String, Any?>()
        ExitReasons.fillAnr(ev, ExitRecord(9, ExitRecord.REASON_ANR, 1, 400, null, "com.shop") { null }, inApp)
        val value = J.obj(J.list(J.obj(ev["exception"])!!["values"])!!.single())!!
        assertEquals("Background ANR", value["value"])
        assertNull(value["stacktrace"])
        assertNull(ev["threads"])
    }

    @Test
    fun tombstonesGiveEveryThreadWithBuildIdsAndSignal() {
        val stone = Tombstone.parse(tombstone())!!
        assertEquals(123, stone.pid)
        assertEquals(124, stone.tid)
        assertEquals(11, stone.signal)
        assertEquals("SIGSEGV", stone.signalName)
        assertEquals(2, stone.threads.size)

        val ev = linkedMapOf<String, Any?>()
        ExitReasons.fillNativeFromTombstone(ev, exit(123, ExitRecord.REASON_CRASH_NATIVE, 1), stone, "com.shop")
        val value = J.obj(J.list(J.obj(ev["exception"])!!["values"])!!.single())!!
        assertEquals("SIGSEGV", value["type"])
        assertEquals("tombstone", J.obj(J.obj(value["mechanism"])!!["data"])!!["source"])
        val frames = J.list(J.obj(value["stacktrace"])!!["frames"])!!.map { J.obj(it)!! }
        assertEquals("checkout_native", frames.last()["function"])
        assertEquals("0x7a00001234", frames.last()["instructionAddr"])
        assertEquals("every frame names its exact image", "0x7a00000000", frames.last()["imageAddr"])
        assertEquals(true, frames.last()["inApp"])
        val threads = J.list(ev["threads"])!!.map { J.obj(it)!! }
        assertEquals(listOf(124, 123), threads.map { it["id"] })
        assertEquals(true, threads[0]["crashed"])
        val image = J.obj(J.list(ev["debugImages"])!!.single())!! // libc is a system library: no image
        assertEquals("elf", image["type"])
        assertEquals("/data/app/~~x/com.shop-1/lib/arm64/libshop.so", image["codeFile"])
        assertEquals("0x7a00000000", image["imageAddr"])
        assertEquals(ElfBuildId.format(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11)), image["debugId"])
    }

    @Test
    fun ourRecordKeepsItsCrashingThreadAndGainsTheTombstonesOthers() {
        val ev = linkedMapOf<String, Any?>(
            "threads" to listOf(linkedMapOf("id" to 124L, "name" to "RenderThread", "crashed" to true, "current" to true)),
            "debugImages" to listOf(linkedMapOf("type" to "elf", "codeFile" to "/data/app/~~x/com.shop-1/lib/arm64/libshop.so", "debugId" to "own")),
        )
        ExitReasons.addTombstoneThreads(ev, Tombstone.parse(tombstone())!!, "com.shop")
        val threads = J.list(ev["threads"])!!.map { J.obj(it)!! }
        assertEquals(listOf(124L, 123L), threads.map { (it["id"] as Number).toLong() })
        assertEquals("RenderThread", threads[0]["name"])
        val images = J.list(ev["debugImages"])!!.map { J.obj(it)!! }
        assertEquals("own", images.single { it["codeFile"].toString().endsWith("libshop.so") }["debugId"])
    }

    @Test
    fun aBrokenTombstoneIsIgnored() {
        assertNull(Tombstone.parse(byteArrayOf(0x0a, 0x7f, 0x01)))
        assertNull(Tombstone.parse(ByteArray(0)))
        val ev = linkedMapOf<String, Any?>()
        ExitReasons.fillNativeFromTombstone(ev, exit(5, ExitRecord.REASON_CRASH_NATIVE, 1), null, "com.shop")
        assertEquals("NativeCrash", J.obj(J.list(J.obj(ev["exception"])!!["values"])!!.single())!!["type"])
    }

    @Test
    fun jvmCrashesListEveryOtherThreadMainFirst() {
        val crashed = Thread("worker")
        val main = Thread("main")
        val other = Thread("io")
        val stacks = mapOf(
            crashed to arrayOf(StackTraceElement("com.shop.Worker", "run", "Worker.kt", 3)),
            other to arrayOf(StackTraceElement("java.lang.Object", "wait", null, -2)),
            main to arrayOf(StackTraceElement("com.shop.Main", "loop", "Main.kt", 9)),
        )
        val threads = ThreadDump.threads(crashed, crashed, inApp, stacks = stacks, mainThread = main)
        assertEquals(listOf("worker", "main", "io"), threads.map { it["name"] })
        assertEquals(true, threads[0]["crashed"])
        assertNull("the exception has the crashing stack", threads[0]["stacktrace"])
        assertNotNull(threads[1]["stacktrace"])
    }

    // ------------------------------------------------------------ fixtures

    /** The fixture tombstone (pid 123, crashing tid 124, SIGSEGV), for other tests. */
    fun tombstoneBytes(): ByteArray = tombstone()

    private fun tombstone(): ByteArray {
        val lib = "/data/app/~~x/com.shop-1/lib/arm64/libshop.so"
        val buildId = "0102030405060708090a0b0c0d0e0f1011"
        fun frame(relPc: Long, pc: Long, fn: String, file: String, id: String?) = msg {
            varint(1, relPc); varint(2, pc); string(4, fn); string(6, file); if (id != null) string(8, id)
        }
        val crashing = msg {
            varint(1, 124); string(2, "RenderThread")
            bytes(4, frame(0x1234, 0x7a00001234, "checkout_native", lib, buildId))
            bytes(4, frame(0x9000, 0x7f00009000, "__start_thread", "/apex/com.android.runtime/lib64/bionic/libc.so", "aa"))
        }
        val main = msg {
            varint(1, 123); string(2, "com.shop")
            bytes(4, frame(0x2000, 0x7a00002000, "loop", lib, buildId))
        }
        return msg {
            varint(5, 123); varint(6, 124)
            bytes(10, msg { varint(1, 11); string(2, "SIGSEGV"); varint(3, 1); varint(9, 0) })
            string(14, "")
            bytes(16, msg { varint(1, 124); bytes(2, crashing) })
            bytes(16, msg { varint(1, 123); bytes(2, main) })
            bytes(17, msg { string(1, "ignored mapping") })
        }
    }

    private class Msg {
        val out = ByteArrayOutputStream()
        private fun raw(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7f.inv().toLong() == 0L) { out.write(x.toInt()); return }
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
        }
        fun varint(field: Int, v: Long) { raw((field shl 3).toLong()); raw(v) }
        fun varint(field: Int, v: Int) = varint(field, v.toLong())
        fun bytes(field: Int, b: ByteArray) { raw(((field shl 3) or 2).toLong()); raw(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
    }

    private fun msg(build: Msg.() -> Unit): ByteArray = Msg().apply(build).out.toByteArray()

    companion object {
        val ANR_TRACE = """
            ----- pid 12345 at 2026-10-26 10:00:00.000 -----
            Cmd line: com.shop

            "main" prio=5 tid=1 Sleeping
              | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a1 self=0xb400
              | sysTid=12345 nice=-10 cgrp=top-app sched=0/0 handle=0x7b
              native: #00 pc 000000000009d5d4  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+4) (BuildId: 6a9c)
              at java.lang.Thread.sleep(Native method)
              - sleeping on <0x0d2ee7a5> (a java.lang.Object)
              at java.lang.Thread.sleep(Thread.java:450)
              at com.shop.CheckoutActivity.onClick(CheckoutActivity.kt:42)
              at android.os.Looper.loop(Looper.java:288)
              at android.app.ActivityThread.main(ActivityThread.java:7839)

            "Signal Catcher" daemon prio=10 tid=6 Runnable
              | sysTid=12351 nice=-20 cgrp=top-app
              native: #00 pc 000000000004cbd8  /apex/com.android.art/lib64/libart.so (art::DumpNativeStack+108)
              native: #01 pc 0000000000001000  /data/app/~~x/com.shop-1/base.apk (offset 0x9000) (Java_com_shop_Native_run+12)

            "binder:12345_1" prio=5 (not attached)
              | sysTid=12352 nice=0 cgrp=top-app

            "OkHttp Dispatcher" prio=5 tid=19 Blocked
              | sysTid=12360 nice=0
              at com.shop.net.Cache.get(Cache.kt:10)
              - waiting to lock <0x01> (a java.lang.Object) held by thread 1

            ----- end 12345 -----
        """.trimIndent()
    }
}
