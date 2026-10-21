package dev.bugcapsule.android.core

import java.io.File

@Suppress("UNCHECKED_CAST")
object SpecFixtures {
    val dir: File = File(System.getProperty("bugcapsule.specFixtures") ?: "../spec-fixtures")
    val upstream: File = File(System.getProperty("bugcapsule.upstreamFixtures") ?: "../../bug_capsule_flutter_sdk/spec/fixtures")

    fun json(relative: String): Map<String, Any?> = Json.parse(File(dir, relative).readText()) as Map<String, Any?>

    fun files(relative: String): List<File> =
        File(dir, relative).listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
}
