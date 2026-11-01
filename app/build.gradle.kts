import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

// La chiave di firma NON sta nel repository: la compilazione su GitHub la ricrea dal secret XONOS_KEYSTORE,
// e la password arriva dal secret XONOS_KEYSTORE_PASSWORD (il repository è pubblico: qui non si scrive).
// Senza chiave la release non è firmata (F-Droid la firma da sé); con XONOS_DEBUG_SIGNING=true (le misure
// sull'emulatore) si firma con la chiave di debug di Android, così si installa lo stesso.
val xonosKeystore = file("xonos.keystore")
val hasXonosKey = xonosKeystore.exists()
val keystorePassword = System.getenv("XONOS_KEYSTORE_PASSWORD")?.ifBlank { null }
val debugSigning = System.getenv("XONOS_DEBUG_SIGNING") == "true"

// Il pacchetto di Music Assistant sul telefono che questa versione usa (gradle.properties): Xonos lo scarica su
// richiesta dalla release di GitHub di questa versione (server/package/make_payload.py lo costruisce).
val serverPackage: String = providers.gradleProperty("xonosServerPackage").get()

android {
    namespace = "it.xonos.ma"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.xonos.ma"
        minSdk = 26
        targetSdk = 34
        // Si alzano entrambi a ogni pubblicazione in main: la release prende il tag v<versionName>, che
        // Obtainium confronta con l'app installata, e F-Droid compila da quel tag.
        versionCode = 48
        versionName = "0.1.48"
        buildConfigField("String", "SERVER_PACKAGE", "\"$serverPackage\"")
        buildConfigField(
            "String",
            "SERVER_PACKAGE_URL",
            "\"https://github.com/SirioNero/Music-Assistant-on-Gearslip/releases/download/v$versionName/\"",
        )
    }

    signingConfigs {
        if (hasXonosKey) {
            create("xonos") {
                storeFile = xonosKeystore
                storePassword = keystorePassword ?: error("Manca XONOS_KEYSTORE_PASSWORD, la password di xonos.keystore")
                // Il workflow legge l'alias dalla chiave stessa: va bene anche quella di Plancia.
                keyAlias = System.getenv("XONOS_KEY_ALIAS")?.ifBlank { null } ?: "xonos"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasXonosKey) signingConfig = signingConfigs.getByName("xonos")
        }
        getByName("release") {
            // R8 toglie il codice che non serve e ottimizza quello che resta (Compose ne ha bisogno per
            // essere fluido). Senza offuscare i nomi: gli errori nei log restano leggibili. Vedi proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Le release si pubblicano solo con la chiave di Xonos; senza, l'APK resta non firmato (vedi sopra).
            when {
                hasXonosKey -> signingConfig = signingConfigs.getByName("xonos")
                debugSigning -> signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // L'elenco delle librerie che AGP mette nell'APK è cifrato per Google: F-Droid non lo accetta.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Il profilo generato (vedi baselineprofile/) sta in src/main/generated/baselineProfiles.
baselineProfile {
    mergeIntoMain = true
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    // Installa il Baseline Profile anche quando l'APK non arriva dal Play Store (Obtainium).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Lo schermo del telefono. Le versioni sono quelle di Compose Multiplatform 1.9.3, che il banco di
    // prova (tools/screens) usa per disegnare le stesse schermate sulla JVM.
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.9.4")
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    // Le icone dei servizi di Music Assistant sono SVG.
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")
}
