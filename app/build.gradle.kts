import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Fester Signaturschluessel aus dem Repo.
 *
 * Android erlaubt ein Update nur, wenn das neue APK mit demselben Schluessel
 * signiert ist wie das installierte. Ohne festen Schluessel signiert jeder
 * CI-Lauf mit einem frisch erzeugten Debug-Schluessel - und jedes Update
 * scheitert. Was das fuer die Sicherheit bedeutet, steht im README.
 */
val signingProperties = Properties().apply {
    rootProject.file("keystore/keystore.properties").inputStream().use { load(it) }
}

/** Der CI reicht die Laufnummer durch, damit die Version monoton steigt. */
val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "de.localvoice.livechat"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.localvoice.livechat"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"

        ndk {
            // Die Nativbibliotheken von LiteRT-LM sind gross. Ohne Filter landen sie
            // fuer vier Architekturen im APK - x86 braucht hier niemand, und
            // Telefone sind seit Jahren arm64. Das spart den grossen Teil der
            // Downloadgroesse und der Packzeit.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("shared") {
            storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Beide Varianten mit demselben Schluessel: sonst laesst sich ein
        // selbst gebauter Stand nicht ueber den aus dem CI installieren.
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            // Ohne Verkleinern - das Ziel ist nur, dass debuggable aus ist.
            // Ein debugfaehiges APK bremst die Inferenz auf dem Geraet spuerbar.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Die kotlinOptions-DSL von AGP ist ab Kotlin 2.2 abgekuendigt.
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Ein einzelner Lint-Fund soll den CI nicht rot machen.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
