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
        versionCode = 7
        versionName = "0.4.0"
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

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
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
