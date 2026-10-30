package dev.bugcapsule.patch.compose

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SurfaceImagesTest {
    private fun document() = JSONObject("""{"type":"column","children":[{"type":"button","label":"Pay","action":"pay"},{"type":"list","children":[{"type":"image","asset":"brand.logo","label":"Brand logo"}]}]}""")

    @Test fun allReferencedImagesAvailable() {
        assertTrue(surfaceImagesAvailable(document(), setOf("brand.logo")))
    }
    @Test fun missingNestedImageRejectsWholeTreeEvenWhenOffscreen() {
        assertFalse(surfaceImagesAvailable(document(), emptySet()))
        assertFalse(surfaceImagesAvailable(document(), setOf("other.logo")))
    }
    @Test fun surfacesWithoutImagesRemainCompatible() {
        assertTrue(surfaceImagesAvailable(JSONObject("""{"type":"text","text":"Checkout"}"""), emptySet()))
    }
    @Test fun everyImageIsRequiredAndMustHaveLabel() {
        val root = document()
        root.getJSONArray("children").put(JSONObject("""{"type":"image","asset":"badge","label":"Verified"}"""))
        assertFalse(surfaceImagesAvailable(root, setOf("brand.logo")))
        assertTrue(surfaceImagesAvailable(root, setOf("brand.logo", "badge")))
        root.getJSONArray("children").getJSONObject(2).remove("label")
        assertFalse(surfaceImagesAvailable(root, setOf("brand.logo", "badge")))
    }
}
