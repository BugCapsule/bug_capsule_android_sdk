package dev.bugcapsule.capsulelab

import android.app.Application
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.patch.BugCapsulePatchBinding
import dev.bugcapsule.patch.PatchConfig
import dev.bugcapsule.patch.PatchSurfaceContract
import java.io.File

private const val TRUST_ANCHOR = "bcpk.v1.dev-root-1.BA4PNvbk7UmdPR93U56lG_Bpqwq3WgzIrI3zk7IuT-hKhRymUfOY4eQFQGei0tjUkzmpyqaHCZf3MtgxbBxq68U"

private val summarySurface = PatchSurfaceContract(
    id = "lab.summary",
    components = listOf("column", "row", "text", "spacer", "button"),
    actions = listOf("lab.pay"),
    requiredActions = listOf("lab.pay"),
)

class CapsuleLabApp : Application() {
    lateinit var binding: BugCapsulePatchBinding

    override fun onCreate() {
        super.onCreate()
        binding = BugCapsulePatchBinding(
            PatchConfig(
                storeDir = File(filesDir, "bcpatch").path,
                projectId = "demo",
                applicationId = packageName,
                environment = "staging",
                distributionProfile = "development",
                baseBuildId = "android-capsule-1",
                installationId = "device-1",
                trustRoots = listOf(TRUST_ANCHOR),
            ),
            listOf(coupon),
            listOf(summarySurface),
        )
        BugCapsule.init(
            this,
            BugCapsuleConfig.builder()
                .mode(CaptureMode.QA)
                .gitSha("capsule-lab")
                .autoCaptureOnFailure(false)
                .patchBinding(binding)
                .build(),
        )
    }
}
