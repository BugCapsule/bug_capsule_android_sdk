package dev.bugcapsule.patch

/** JNI entries of the bundled runtime (spec/abi-v1.md). Each answer starts with a status byte. */
internal object NativeBridge {
    const val OP_STATUS = 1
    const val OP_STAGE = 2
    const val OP_CHANNEL = 3
    const val OP_KEYRING = 4
    const val OP_HEALTHY = 5
    const val OP_FAULT = 6
    const val OP_EVAL = 7
    const val OP_ASSET = 8
    const val OP_RECEIPTS = 9
    const val OP_ACK = 10
    const val OP_LOCAL_EVAL = 12

    const val ABI_VERSION = 1

    /** False when the build carries no runtime for this device. */
    val loaded: Boolean = try {
        System.loadLibrary("bcpatch")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    @JvmStatic external fun abiVersion(): Int

    @JvmStatic external fun open(config: ByteArray): ByteArray

    @JvmStatic external fun call(handle: Long, op: Int, a: ByteArray?, b: ByteArray?, n: Long): ByteArray

    @JvmStatic external fun close(handle: Long)
}
