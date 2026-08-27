package app.mammon

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Packaging invariant: the SAF provider authority must stay guarded by
 * MANAGE_DOCUMENTS. DocumentsProvider.attachInfo throws SecurityException at
 * bind time otherwise, killing every process of the app on launch (reproduced
 * on Android 13). Parsed as XML, not source text, so formatting is irrelevant.
 */
class ManifestGuardTest {
    @Test
    fun nfsProviderRequiresManageDocuments() {
        val permission = nfsProvider().getAttribute("android:permission")
        assertEquals(
            "provider must carry android:permission=android.permission.MANAGE_DOCUMENTS",
            "android.permission.MANAGE_DOCUMENTS",
            permission,
        )
    }

    /**
     * Every post-mutation notifyChange URI is built from SAF_AUTHORITY. Drift from the
     * declaration makes each refresh land on an authority nothing serves, and the only
     * symptom is a stale listing.
     */
    @Test
    fun nfsProviderAuthorityMatchesTheNotifyConstant() {
        assertEquals(
            "SAF_AUTHORITY must equal the declared android:authorities",
            SAF_AUTHORITY,
            nfsProvider().getAttribute("android:authorities"),
        )
    }

    private fun nfsProvider(): Element = providersNamed {
        it == ".NfsDocumentsProvider" || it == "app.mammon.NfsDocumentsProvider"
    }

    private fun providersNamed(matches: (String) -> Boolean): Element {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${manifest.absolutePath}", manifest.isFile)

        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        val doc = factory.newDocumentBuilder().parse(manifest)

        val providers = doc.getElementsByTagName("provider")
        val ours = (0 until providers.length)
            .map(providers::item)
            .filterIsInstance<Element>()
            .filter { matches(it.getAttribute("android:name")) }

        assertEquals("expected exactly one matching provider declaration", 1, ours.size)
        return ours.single()
    }

    /**
     * Packaging invariant: the module hand-off provider must stay unexported. An
     * exported FileProvider would serve any cached file to every app on the device.
     */
    @Test
    fun moduleFileProviderStaysUnexported() {
        val provider = providersNamed { it.contains("FileProvider") }
        assertEquals(
            "FileProvider must not be exported",
            "false",
            provider.getAttribute("android:exported"),
        )
        assertEquals(
            "FileProvider must grant URI permissions so the chooser target can read the zip",
            "true",
            provider.getAttribute("android:grantUriPermissions"),
        )
        assertEquals(
            "FileProvider authority must match ModuleInstall.FILE_PROVIDER_AUTHORITY",
            ModuleInstall.FILE_PROVIDER_AUTHORITY,
            provider.getAttribute("android:authorities"),
        )
    }
}
