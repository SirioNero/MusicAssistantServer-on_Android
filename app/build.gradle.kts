import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.process.ExecOperations
import javax.inject.Inject

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
    // L'NDK che compila proot (native/proot.sh): fissato, perché l'APK di F-Droid deve venire identico al nostro.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "it.xonos.ma"
        minSdk = 26
        targetSdk = 34
        // Si alzano entrambi a ogni pubblicazione in main: la release prende il tag v<versionName>, che
        // Obtainium confronta con l'app installata, e F-Droid compila da quel tag.
        versionCode = 56
        versionName = "0.1.56"
        buildConfigField("String", "SERVER_PACKAGE", "\"$serverPackage\"")
        buildConfigField(
            "String",
            "SERVER_PACKAGE_URL",
            "\"https://github.com/SirioNero/MusicAssistantServer-on_Android/releases/download/v$versionName/\"",
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

// proot, compilato dal sorgente (native/proot.sh), fa girare i programmi per Linux del server sul telefono
// (Soloist di Spotify e PulseAudio). Solo per i telefoni a 64 bit, come il server.
abstract class BuildProot : DefaultTask() {
    @get:InputFiles
    abstract val scripts: ConfigurableFileCollection

    @get:Input
    abstract val ndk: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val script = scripts.files.first { it.name == "proot.sh" }
        execOperations.exec {
            commandLine(listOf("bash", script.path, ndk.get(), out.path) + abis.get())
        }
    }
}

val buildProot = tasks.register<BuildProot>("buildProot") {
    description = "Compila proot per Android dal sorgente (native/proot.sh)."
    scripts.from("native/proot.sh", "native/talloc-replace.h", "native/loader-info.awk")
    ndk.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.path })
    abis.set(listOf("arm64-v8a", "x86_64"))
    outputDir.set(layout.buildDirectory.dir("proot"))
}

// Le librerie si estraggono su disco all'installazione: da Android 10 un'app può avviare un programma solo dalla
// cartella delle sue librerie, e proot è un programma.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildProot, BuildProot::outputDir)
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
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
