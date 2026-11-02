import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Python per Android (MIT). La versione è qui e non nel progetto principale: la build di F-Droid,
    // che toglie server/, non lo scarica nemmeno.
    id("com.chaquo.python") version "17.2.0"
}

// Il server di Music Assistant dentro Xonos: Python 3.13 (Chaquopy), Music Assistant preparato da
// ma/prepare.py e i pacchetti di requirements.txt. Per compilarlo serve Python 3.13 sulla macchina
// (python3.13 sul PATH, o il percorso nella proprietà xonosPython) e la rete: il sorgente di Music
// Assistant e i pacchetti si scaricano.
val pythonCommand: String = providers.gradleProperty("xonosPython").getOrElse("python3.13")
val musicAssistantVersion = "2.10.8"
val preparedSources = layout.buildDirectory.dir("music-assistant")
val serverWheels = layout.buildDirectory.dir("wheels")

val prepareMusicAssistant by tasks.registering(Exec::class) {
    description = "Prepara il sorgente di Music Assistant per il telefono."
    inputs.file("ma/prepare.py")
    inputs.file("ma/strings.json")
    inputs.file("ma/ma-strings.json")
    inputs.property("version", musicAssistantVersion)
    outputs.dir(preparedSources)
    commandLine(
        pythonCommand, file("ma/prepare.py").path,
        "--version", musicAssistantVersion,
        "--out", preparedSources.get().asFile.path,
    )
}

val prepareServerWheels by tasks.registering(Exec::class) {
    description = "Costruisce le wheel al posto di quelle che per Android non ci sono."
    inputs.file("ma/wheels.py")
    inputs.dir("wheels")
    outputs.dir(serverWheels)
    commandLine(pythonCommand, file("ma/wheels.py").path, "--out", serverWheels.get().asFile.path)
}

// I programmi che Music Assistant esegue, compilati dal sorgente con l'NDK: ffmpeg e ffprobe (senza, il
// server non parte; vedi ffmpeg/build.sh) e librespot per Spotify (librespot/build.sh). Gli script non
// rifanno niente se l'uscita è già della loro versione. L'NDK è quello di ANDROID_NDK, o ANDROID_NDK_HOME /
// ANDROID_NDK_LATEST_HOME come sulle macchine di GitHub.
val nativeTools = listOf("ffmpeg", "librespot")
val androidNdk: Provider<String> = providers.environmentVariable("ANDROID_NDK")
    .orElse(providers.environmentVariable("ANDROID_NDK_HOME"))
    .orElse(providers.environmentVariable("ANDROID_NDK_LATEST_HOME"))
val serverAbis = providers.gradleProperty("xonosServerAbis").get().split(',').map(String::trim)
val buildTools = nativeTools.flatMap { tool ->
    serverAbis.map { abi ->
        val suffix = (listOf(tool) + abi.split('-', '_')).joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }
        // Senza uscite dichiarate: Gradle svuoterebbe la cartella alla prima esecuzione del task (anche
        // con ffmpeg appena ripreso dalla cache di GitHub). Il task parte sempre; lo script decide.
        tasks.register<Exec>("build$suffix") {
            description = "Compila $tool per Android ($abi)."
            commandLine("bash", file("$tool/build.sh").path, abi, layout.buildDirectory.dir(tool).get().asFile.path)
            doFirst {
                environment(
                    "ANDROID_NDK",
                    androidNdk.orNull ?: throw GradleException("Per compilare $tool serve ANDROID_NDK (la cartella dell'NDK)."),
                )
            }
        }
    }
}

android {
    namespace = "it.xonos.ma.server"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // Le ABI di xonosServerAbis (gradle.properties): Python 3.13 per Android c'è solo a 64 bit.
        ndk {
            abiFilters += serverAbis
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            nativeTools.forEach { tool -> jniLibs.srcDir(layout.buildDirectory.dir(tool)) }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython(pythonCommand)
        // Music Assistant legge da disco i suoi file (manifest dei servizi, traduzioni, icone) e guarda
        // quali servizi hanno setup_flow.py: si estrae tutto, .py compresi.
        extractPackages("music_assistant")
        pip {
            options("--find-links", serverWheels.get().asFile.path)
            install("-r", "requirements.txt")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir(preparedSources)
        }
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("PythonSources")) dependsOn(prepareMusicAssistant)
    if (name.startsWith("install") && name.endsWith("PythonRequirements")) dependsOn(prepareServerWheels)
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) dependsOn(buildTools)
}
