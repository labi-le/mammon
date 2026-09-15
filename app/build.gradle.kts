import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties
import java.security.MessageDigest
import java.util.zip.ZipFile
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mammon"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.mammon"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.9.0"
    }

    // Local releases sign when keystore.properties exists; absent file keeps them unsigned (CI parity).
    val keystorePropertiesFile = File(System.getProperty("user.home"), ".config/mammon/keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

// The Magisk module zip is a build product, not a checked-in blob: Gradle's own Zip
// task packs magisk-module/ deterministically (fixed order and timestamp, unix modes
// kept so the installer scripts stay executable), and a bridge task exposes the
// zip-bearing directory as the generated assets dir the Variant API requires.
val moduleFiles = fileTree(rootProject.file("magisk-module"))
moduleFiles.exclude(".shellcheckrc")

val packModuleZip = tasks.register<Zip>("packModuleZip") {
    // Both flags together make rebuilds byte-identical: entries get a constant
    // timestamp instead of per-file mtimes or the build time.
    from(moduleFiles)
    archiveFileName.set("mammon-module.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    destinationDirectory.set(temporaryDir)
}

// The unit tests read the real zip and the real fslib.sh, not fixtures, so the zip-root
// layout and the shell half of the app/module pair stay tested.
tasks.withType<Test>().configureEach {
    dependsOn(packModuleZip)
    // A systemProperty makes the path an input, never its bytes: without these an
    // fslib.sh or zip edit left this task UP-TO-DATE and shell mutations read green.
    inputs.file(packModuleZip.flatMap { it.archiveFile })
        .withPropertyName("moduleZip")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.file(rootProject.file("magisk-module/fslib.sh"))
        .withPropertyName("fslibScript")
        .withPathSensitivity(PathSensitivity.NONE)
    systemProperty("mammon.moduleZip", packModuleZip.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("mammon.fslib", rootProject.file("magisk-module/fslib.sh").absolutePath)
}

abstract class ModuleAssetDirTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zipFile: RegularFileProperty

    /** addGeneratedSourceDirectory needs a producer exposing a DirectoryProperty,
     *  which the Zip task's RegularFileProperty is not - the failed shape this
     *  bridge replaces. */
    @get:OutputDirectory
    abstract val assetDir: DirectoryProperty

    @TaskAction
    fun expose() {
        val dir = assetDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        zipFile.get().asFile.copyTo(File(dir, "mammon-module.zip"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val assetDirProvider = tasks.register(
            "moduleZipAssets${variant.name.replaceFirstChar { ch -> ch.uppercase() }}",
            ModuleAssetDirTask::class.java,
        ) {
            zipFile.set(packModuleZip.flatMap { zip -> zip.archiveFile })
            assetDir.set(layout.buildDirectory.dir("generated/module-zip/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(assetDirProvider) { task -> task.assetDir }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// nfs-client 1.1.0 reassembles multi-fragment RPC replies four bytes off, and its record decoder
// only assembles a record whose every fragment arrives in one netty cumulation pass; the fixed
// copies of RecordMarkingUtil and RPCRecordDecoder live in app/src/main/java. D8 rejects two
// definitions of one class, so the jar is repacked without those entries, with coordinates left
// as a declaration to bump.
val nfsClientOriginal: Configuration by configurations.creating { isTransitive = false }

// SHA-256 of each entry's uncompressed bytes as published in com.emc.ecs:nfs-client:1.1.0.
// Pinning content, not presence, is what makes an upstream repair visible: a fixed class keeps
// its path, so a presence check would pass and our copy would silently revert the fix.
val shadowedNfsClientEntries = mapOf(
    "com/emc/ecs/nfsclient/network/RecordMarkingUtil.class"
        to "2c399221b6a741118281c91db8375957cc0f6e86b06f1843ca048b93d2871677",
    "com/emc/ecs/nfsclient/network/RPCRecordDecoder.class"
        to "15ea3e73a4988465d08b6e8b2057814d0eed68692877eacd1d5dbc6e14fb6c02",
)

val stripShadowedNfsClient = tasks.register<Jar>("stripShadowedNfsClient") {
    val originalJars = files(nfsClientOriginal)
    val pins = shadowedNfsClientEntries
    from(originalJars.elements.map { jars -> jars.map { jar -> zipTree(jar.asFile) } })
    pins.keys.forEach { entry -> exclude(entry) }
    // The Jar task writes its own manifest, so the incoming one would be a duplicate entry.
    exclude("META-INF/MANIFEST.MF")
    // The guard below runs in doFirst, so it runs only when the task is out of date, and the
    // CopySpec's tracked source carries neither the pinned hashes nor - exclude filters it - the
    // pinned entries' bytes. Declaring the pins and the whole resolved jars restores both, so a
    // pin edit alone, or a jar doctored only in those entries, reruns the check.
    inputs.property("shadowedNfsClientEntries", pins)
    inputs.files(originalJars)
        .withPropertyName("nfsClientOriginalJars")
        .withPathSensitivity(PathSensitivity.NONE)
    val vendoredShadowRoot = layout.projectDirectory.dir("src/main/java")
    val vendoredShadowSources = files(
        pins.keys.map { entry ->
            vendoredShadowRoot.file("${entry.removeSuffix(".class")}.java").asFile
        }
    )
    // The pins alone leave the source the guard protects untracked, so deleting it read
    // UP-TO-DATE and the guard never ran.
    inputs.files(vendoredShadowSources)
        .withPropertyName("vendoredShadowSources")
        .withPathSensitivity(PathSensitivity.NONE)
    archiveFileName.set("nfs-client-stripped.jar")
    destinationDirectory.set(layout.buildDirectory.dir("shadowed-libs"))
    doFirst {
        val jars = originalJars.files
        val resolved = jars.joinToString(", ") { jar -> jar.name }
        pins.forEach { (entryName, expectedHash) ->
            val vendoredPath = "${entryName.removeSuffix(".class")}.java"
            val vendored = "app/src/main/java/$vendoredPath"
            if (!vendoredShadowRoot.file(vendoredPath).asFile.exists()) {
                throw GradleException(
                    "$vendored is gone, but $entryName is still pinned here, so the repack still " +
                        "strips it and nothing replaces it: R8 fails the release build, while a " +
                        "debug build links lazily and dies at runtime inside " +
                        "ClientIOHandler.messageReceived as a closed connection. Restore " +
                        "$vendored from git, or - if the shadow is deliberately gone - delete " +
                        "its entry here too, and - once no entries are left - this task and the " +
                        "nfsClientOriginal configuration, and depend on com.emc.ecs:nfs-client " +
                        "directly again."
                )
            }
            val carriers = jars.mapNotNull { jar ->
                ZipFile(jar).use { zip ->
                    // A duplicate entry name, where getEntry hashes one copy and the exclude
                    // strips both, is dependency verification's threat, not this guard's.
                    zip.getEntry(entryName)?.let { entry ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.getInputStream(entry).use { bytes ->
                            val chunk = ByteArray(8192)
                            while (true) {
                                val read = bytes.read(chunk)
                                if (read < 0) break
                                digest.update(chunk, 0, read)
                            }
                        }
                        jar.name to digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                    }
                }
            }
            if (carriers.isEmpty()) {
                throw GradleException(
                    "$entryName is missing from the resolved nfs-client jar ($resolved), so the " +
                        "shadow in $vendored now shadows nothing. Diff $vendored against the new " +
                        "upstream: if the class only moved, point shadowedNfsClientEntries at its " +
                        "new path and re-pin; if it is gone because the library was repaired, " +
                        "delete $vendored and its entry here, and - once no entries are left - " +
                        "this task and the nfsClientOriginal configuration, and depend on " +
                        "com.emc.ecs:nfs-client directly again."
                )
            }
            if (carriers.size > 1) {
                val listed = carriers.joinToString(", ") { (jar, hash) -> "$jar: SHA-256 $hash" }
                throw GradleException(
                    "$entryName is carried by ${carriers.size} of the jars nfsClientOriginal " +
                        "resolved ($listed), and one pin can only speak for one copy: the exclude " +
                        "above strips this entry from every jar, so a second, differing copy would " +
                        "be stripped and never hashed and this guard would still report green. " +
                        "nfsClientOriginal is meant to resolve exactly one jar - it is " +
                        "non-transitive with a single coordinate - so remove the coordinate that " +
                        "brought in the extra jar; if both jars genuinely have to stay, pin every " +
                        "copy of this entry rather than the first one found."
                )
            }
            val (jarName, actualHash) = carriers.single()
            if (actualHash != expectedHash) {
                throw GradleException(
                    "$entryName in the resolved nfs-client jar ($jarName) no longer matches the " +
                        "pinned bytes: expected SHA-256 $expectedHash, got $actualHash. Upstream " +
                        "changed this class. Diff $vendored against the new upstream: if the " +
                        "defect is fixed there, delete $vendored and its entry here rather than " +
                        "letting our copy revert the fix; if it is not, port the other upstream " +
                        "changes into $vendored and update the pin to $actualHash."
                )
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    nfsClientOriginal("com.emc.ecs:nfs-client:1.1.0")
    implementation(files(stripShadowedNfsClient))
    // The stripped jar arrives as a file, so the three runtime dependencies the nfs-client POM
    // declares have to be named here. netty is the version the POM declares; commons-lang3 and
    // slf4j-api are bumps off its 3.12.0 and 1.7.36, because naming them made lint's
    // NewerVersionAvailable fire and this project's gate is zero new lint findings.
    implementation("io.netty:netty:3.10.6.Final")
    implementation("org.apache.commons:commons-lang3:3.20.0")
    implementation("org.slf4j:slf4j-api:2.0.19")
    // Server library reused as a client: it carries the NFSv4.1 XDR types and
    // CompoundBuilder. Berkeley DB backs only its server-side client store.
    implementation("org.dcache:nfs4j-core:0.28.5") {
        exclude(group = "com.sleepycat", module = "je")
    }
    implementation("org.dcache:oncrpc4j-core:3.4.3")
    testImplementation("junit:junit:4.13.2")
}
