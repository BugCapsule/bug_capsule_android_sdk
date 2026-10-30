# Images in patch surfaces

Register each image asset ID in the surface contract's `assets` list and include
`image` among its components. Supply the same IDs to the Compose renderer as
app-owned `ImageBitmap` values, for example images decoded from shipped resources:

```kotlin
val logo = ImageBitmap.imageResource(R.drawable.brand_logo)
PatchSurfaceView(
    patch = patch,
    id = "checkout.summary",
    actions = mapOf("checkout.pay" to ::pay),
    images = mapOf("brand.logo" to logo),
) { BundledCheckoutSummary() }
```

Import `androidx.compose.ui.graphics.ImageBitmap` and
`androidx.compose.ui.res.imageResource`. The signed surface can contain
`{"type":"image","asset":"brand.logo","label":"Brand logo"}`. Its `label`
is the native image content description for accessibility.

The app controls image allocation and loading. The renderer never downloads an
image or decodes compressed patch assets. Registration authorizes the asset ID;
the supplied map provides its native image. Extra map entries do not authorize
unregistered IDs: the engine still validates the complete surface contract.

If any referenced image is absent, including a nested or offscreen list image,
the entire bundled surface renders so no required visual silently disappears.
The existing overload without an image map stays source-compatible; it renders
surfaces without images and falls back for surfaces that need images.
