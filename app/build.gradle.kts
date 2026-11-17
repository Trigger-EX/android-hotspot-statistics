import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.triggerex.hotspotstats"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.triggerex.hotspotstats"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // The capture helper is an executable shipped as libhscapture.so; it must be
        // extracted to nativeLibraryDir so it can be run.
        jniLibs.useLegacyPackaging = true
    }
    lint {
        // Hidden-API reflection is intentional and guarded; see HotspotStateReader.
        disable += "PrivateApi"
        disable += "DiscouragedPrivateApi"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

/**
 * Cross-compiles the root packet-capture helper (src/main/c/hscapture.c) for every ABI
 * with the NDK's clang. It is an executable, not a JNI library; the lib*.so name only
 * makes the package manager extract it next to the app's native libraries.
 */
abstract class BuildHscapture : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val os = System.getProperty("os.name").lowercase()
        val host = when {
            "mac" in os -> "darwin-x86_64"
            "win" in os -> "windows-x86_64"
            else -> "linux-x86_64"
        }
        val clang = ndkDirectory.get().asFile
            .resolve("toolchains/llvm/prebuilt/$host/bin/clang" + if ("win" in os) ".exe" else "")
        check(clang.exists()) { "NDK clang not found at $clang" }
        val targets = mapOf(
            "arm64-v8a" to "aarch64-linux-android26",
            "armeabi-v7a" to "armv7a-linux-androideabi26",
            "x86_64" to "x86_64-linux-android26",
            "x86" to "i686-linux-android26",
        )
        for ((abi, target) in targets) {
            val out = outputDir.get().asFile.resolve("$abi/libhscapture.so")
            out.parentFile.mkdirs()
            val cmd = listOf(
                clang.path, "--target=$target", "-O2", "-Wall", "-Wextra", "-Werror",
                "-fPIE", "-pie", "-s", "-Wl,-z,max-page-size=16384",
                "-o", out.path, source.get().asFile.path,
            )
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "hscapture build failed for $abi:\n$log" }
        }
    }
}

val buildHscapture = tasks.register<BuildHscapture>("buildHscapture") {
    source.set(layout.projectDirectory.file("src/main/c/hscapture.c"))
    ndkDirectory.set(layout.dir(provider { android.ndkDirectory }))
    outputDir.set(layout.buildDirectory.dir("generated/hscapture"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildHscapture, BuildHscapture::outputDir)
    }
}
