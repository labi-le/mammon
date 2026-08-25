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
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${manifest.absolutePath}", manifest.isFile)

        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        val doc = factory.newDocumentBuilder().parse(manifest)

        val providers = doc.getElementsByTagName("provider")
        val ours = (0 until providers.length)
            .map(providers::item)
            .filterIsInstance<Element>()
            .filter { it.getAttribute("android:name").let { n -> n == ".NfsDocumentsProvider" || n == "app.mammon.NfsDocumentsProvider" } }

        assertEquals("expected exactly one NfsDocumentsProvider declaration", 1, ours.size)

        val permission = ours.single().getAttribute("android:permission")
        assertEquals(
            "provider must carry android:permission=android.permission.MANAGE_DOCUMENTS",
            "android.permission.MANAGE_DOCUMENTS",
            permission,
        )
    }

    /**
     * Packaging invariant: the module hand-off provider must stay unexported. An
     * exported FileProvider would serve any cached file to every app on the device.
     */
    @Test
    fun moduleFileProviderStaysUnexported() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${manifest.absolutePath}", manifest.isFile)

        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        val doc = factory.newDocumentBuilder().parse(manifest)

        val providers = doc.getElementsByTagName("provider")
        val ours = (0 until providers.length)
            .map(providers::item)
            .filterIsInstance<Element>()
            .filter { it.getAttribute("android:name").contains("FileProvider") }

        assertEquals("expected exactly one FileProvider declaration", 1, ours.size)

        val provider = ours.single()
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
