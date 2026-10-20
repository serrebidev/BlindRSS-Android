import java.net.URI
// yt-dlp on Android: builds the `library` and `ffmpeg` parts of the youtubedl-kotlin submodule
// (third_party/youtubedl-kotlin, GPL-3.0) with this project's toolchain. Bundles Python, FFmpeg and QuickJS
// (yt-dlp's JavaScript runtime for YouTube challenges); arm64 only, Android 11+ at run time.
// The bundled yt-dlp is replaced with the latest nightly at build time; the app also updates it daily.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
val upstream = rootProject.file("third_party/youtubedl-kotlin")
val nightlyRes = layout.buildDirectory.dir("generated/ytdlp-nightly")

android {
    namespace = "com.sipun.youtubedl_android"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    sourceSets["main"].apply {
        manifest.srcFile("$upstream/library/src/main/AndroidManifest.xml")
        java.srcDirs("$upstream/library/src/main/kotlin", "$upstream/ffmpeg/src/main/kotlin")
        jniLibs.srcDirs("$upstream/library/src/main/jniLibs", "$upstream/ffmpeg/src/main/jniLibs")
        res.srcDirs(nightlyRes)
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

// Latest yt-dlp nightly as res/raw/ytdlp; refreshed when older than a day. Offline: the submodule's bundled copy.
val fetchYtdlpNightly by tasks.registering {
    val out = nightlyRes.get().file("raw/ytdlp").asFile
    outputs.file(out)
    outputs.upToDateWhen { out.isFile && System.currentTimeMillis() - out.lastModified() < 86_400_000L }
    doLast {
        out.parentFile.mkdirs()
        try {
            URI("https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/latest/download/yt-dlp").toURL().openStream().use { i -> out.outputStream().use { i.copyTo(it) } }
            check(out.readBytes().take(2) == listOf('#'.code.toByte(), '!'.code.toByte())) { "not a yt-dlp zipapp" }
        } catch (e: Exception) {
            logger.warn("yt-dlp nightly download failed (${e.message}); using the bundled copy")
            File("$upstream/library/src/main/res/raw/ytdlp").copyTo(out, overwrite = true)
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchYtdlpNightly) }

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.22")
    implementation("commons-io:commons-io:2.22.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
}
