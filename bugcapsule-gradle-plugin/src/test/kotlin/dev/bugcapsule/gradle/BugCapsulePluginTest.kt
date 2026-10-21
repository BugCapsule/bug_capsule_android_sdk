package dev.bugcapsule.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BugCapsulePluginTest {
    @Test
    fun `applies to a plain project and registers the extension`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("dev.bugcapsule.gradle")

        val extension = project.extensions.findByName("bugcapsule")
        assertNotNull(extension)
        extension as BugCapsuleExtension
        assertEquals(true, extension.uploadProguardMapping.get())
        assertEquals(true, extension.failOnUploadError.get())
        assertTrue(project.tasks.none { it.name.startsWith("uploadBugCapsule") })
    }

    @Test
    fun `url and key are unset without property or environment and can be configured`() {
        assumeTrue(System.getenv("BUGCAPSULE_URL") == null && System.getenv("BUGCAPSULE_SECRET_KEY") == null)
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(BugCapsulePlugin::class.java)

        val extension = project.extensions.getByType(BugCapsuleExtension::class.java)
        assertFalse(extension.url.isPresent)
        assertFalse(extension.secretKey.isPresent)
        extension.url.set("https://example.test")
        extension.secretKey.set("bcs_x")
        assertEquals("https://example.test", extension.url.get())
        assertEquals("bcs_x", extension.secretKey.get())
    }

    @Test
    fun `applies alongside the java plugin`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.pluginManager.apply("dev.bugcapsule.gradle")
        assertNotNull(project.extensions.findByType(BugCapsuleExtension::class.java))
    }
}
