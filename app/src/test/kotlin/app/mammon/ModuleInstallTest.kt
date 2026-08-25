package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class ModuleInstallTest {
    @Test
    fun cleanEchoesMapToTheirVerdicts() {
        assertEquals(ModuleInstall.Probe.PRESENT, ModuleInstall.classify(0, "present\n"))
        assertEquals(ModuleInstall.Probe.ABSENT, ModuleInstall.classify(0, "absent"))
    }

    @Test
    fun anythingElseIsUnavailableNotAbsent() {
        // A denied or timed-out probe knows nothing about the device; reading it as
        // absent would push the user into reinstalling a module that is already there.
        assertEquals(ModuleInstall.Probe.UNAVAILABLE, ModuleInstall.classify(1, null))
        assertEquals(ModuleInstall.Probe.UNAVAILABLE, ModuleInstall.classify(124, null))
        assertEquals(ModuleInstall.Probe.UNAVAILABLE, ModuleInstall.classify(0, ""))
        assertEquals(ModuleInstall.Probe.UNAVAILABLE, ModuleInstall.classify(0, "present\nabsent"))
    }

    @Test
    fun stagingKeepsTheFixedNameAndTruncates() {
        val dir = Files.createTempDirectory("mammon-stage").toFile()
        try {
            assertEquals(File(dir, ModuleInstall.ASSET_NAME), ModuleInstall.stagedFile(dir))

            val target = ModuleInstall.stagedFile(dir)
            target.writeBytes(ByteArray(32))
            ModuleInstall.stageCopy(ByteArrayInputStream("zip".toByteArray()), target)
            assertEquals("zip", target.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
