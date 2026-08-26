import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties
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
        versionCode = 16
        versionName = "0.6.5"
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

// The unit test reads the real zip, not a fixture, so the zip-root layout stays tested.
tasks.withType<Test>().configureEach {
    dependsOn(packModuleZip)
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.emc.ecs:nfs-client:1.1.0")
    // Server library reused as a client: it carries the NFSv4.1 XDR types and
    // CompoundBuilder. Berkeley DB backs only its server-side client store.
    implementation("org.dcache:nfs4j-core:0.28.5") {
        exclude(group = "com.sleepycat", module = "je")
    }
    implementation("org.dcache:oncrpc4j-core:3.4.3")
    testImplementation("junit:junit:4.13.2")
}
