import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// local.properties: machine-local config (SDK path, keystore passwords,
// Telegram credentials). Not in git - see local.properties.example.
// Signing values fall back to the committed shared keystore so that a fresh
// clone builds out of the box.
// ---------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, default: String? = null): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: default

// ---------------------------------------------------------------------------
// Build counter: version.properties holds a single buildNumber that is
// incremented on every assemble/install/deliver invocation.
// versionCode = buildNumber, versionName = 2.0.<buildNumber>
// ---------------------------------------------------------------------------
// CI passes -PbuildNumber=<run number> instead of touching version.properties.
val versionFile = rootProject.file("version.properties")
val versionProps = Properties().apply { versionFile.inputStream().use { load(it) } }
val overrideBuildNumber = (project.findProperty("buildNumber") as? String)?.toIntOrNull()
val bumpRequested = overrideBuildNumber == null && gradle.startParameter.taskNames.any { t ->
    listOf("assemble", "install", "deliver", "bundle").any { t.contains(it, ignoreCase = true) }
}
val buildNumber: Int = overrideBuildNumber ?: run {
    var n = versionProps.getProperty("buildNumber", "1").trim().toInt()
    if (bumpRequested) {
        n += 1
        versionProps.setProperty("buildNumber", n.toString())
        versionFile.outputStream().use {
            versionProps.store(it, "Build counter. Incremented automatically by Gradle, do not edit by hand.")
        }
    }
    n
}
val appVersionName = "2.0.$buildNumber"
val buildTimestamp: String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(ZonedDateTime.now())

android {
    namespace = "ru.zf.pravka"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.zf.pravka"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = appVersionName
        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")
        // whisper.cpp is built only for the Pixel's arm64 - keeps the APK
        // and the CI native build small (no x86/armv7 the owner never uses).
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // Release flags for the on-device transcription hot path;
                // NEON is on by default for arm64.
                cppFlags += "-O3"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    // NDK r27+ handles Android 15's 16 KB page alignment; pin so CI fetches
    // a known-good one instead of whatever is preinstalled.
    ndkVersion = "27.2.12479018"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // One shared keystore for ALL builds, debug included. Changing the signing
    // key breaks install-over-update and wipes the accumulated dictionary.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file(prop("keystore.path", "keystore/pravka.jks")!!)
            storePassword = prop("keystore.password", "pravka-keystore-2026")
            keyAlias = prop("keystore.alias", "pravka")
            keyPassword = prop("keystore.keyPassword", "pravka-keystore-2026")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // Версия корутин задаётся явно и одна на оба артефакта: ядро тянет
    // coroutines-core, а Dispatchers.Main на Android живёт в -android, и
    // разъехавшиеся версии этой пары дают NoSuchMethodError в рантайме.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
}

// ---------------------------------------------------------------------------
// Delivery tasks
// ---------------------------------------------------------------------------

fun runCommand(vararg cmd: String): Pair<Int, String> = try {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).directory(rootProject.projectDir).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    p.exitValue() to out.trim()
} catch (e: Exception) {
    -1 to (e.message ?: "")
}

fun lastCommitSubject(): String {
    val (code, out) = runCommand("git", "log", "-1", "--pretty=%s")
    return if (code == 0 && out.isNotBlank()) out.lines().first() else "no commit info"
}

fun sendDocumentToTelegram(token: String, chatId: String, file: File, caption: String) {
    val boundary = "----PravkaBoundary${System.currentTimeMillis()}"
    val conn = URL("https://api.telegram.org/bot$token/sendDocument")
        .openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.connectTimeout = 15_000
    conn.readTimeout = 120_000
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    conn.outputStream.use { os ->
        fun writeText(s: String) = os.write(s.toByteArray(Charsets.UTF_8))
        fun field(name: String, value: String) {
            writeText("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        field("chat_id", chatId)
        field("caption", caption)
        writeText(
            "--$boundary\r\nContent-Disposition: form-data; name=\"document\"; " +
                "filename=\"${file.name}\"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n"
        )
        file.inputStream().use { it.copyTo(os) }
        writeText("\r\n--$boundary--\r\n")
        os.flush()
    }
    val code = conn.responseCode
    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.readText() ?: ""
    if (code !in 200..299) {
        throw GradleException("Telegram sendDocument failed: HTTP $code $body")
    }
}

fun findAdb(): String? {
    val candidates = mutableListOf<String>()
    prop("sdk.dir")?.let { candidates += "$it/platform-tools/adb" }
    System.getenv("ANDROID_HOME")?.let { candidates += "$it/platform-tools/adb" }
    candidates += "adb" // rely on PATH
    for (c in candidates) {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "$c.exe" else c
        val (code, _) = runCommand(exe, "version")
        if (code == 0) return exe
    }
    return null
}

val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")

tasks.register("deliverApk") {
    group = "delivery"
    description = "Assemble debug APK and send it to the owner's Telegram chat"
    dependsOn("assembleDebug")
    doLast {
        val apk = debugApk.get().asFile
        if (!apk.exists()) throw GradleException("APK not found: $apk")
        val token = prop("telegram.botToken")
        val chatId = prop("telegram.chatId")
        if (token == null || chatId == null) {
            logger.lifecycle(
                "deliverApk: telegram.botToken / telegram.chatId not set in local.properties, " +
                    "skipping Telegram delivery. APK: $apk"
            )
            return@doLast
        }
        val caption = "Pravka $appVersionName (code $buildNumber)\n$buildTimestamp\n${lastCommitSubject()}"
        sendDocumentToTelegram(token, chatId, apk, caption)
        logger.lifecycle("deliverApk: sent ${apk.name} (${apk.length() / 1024} KiB) to Telegram chat $chatId")
    }
}

tasks.register("deliver") {
    group = "delivery"
    description = "Assemble, install over adb when a device is visible, and send to Telegram"
    dependsOn("deliverApk")
    doLast {
        val adb = findAdb()
        if (adb == null) {
            logger.lifecycle("deliver: adb not found, skipping install")
            return@doLast
        }
        val (_, devicesOut) = runCommand(adb, "devices")
        val serials = devicesOut.lines().drop(1)
            .filter { it.trim().endsWith("device") }
            .map { it.split(Regex("\\s+")).first() }
        if (serials.isEmpty()) {
            logger.lifecycle("deliver: no adb device visible, skipping install")
            return@doLast
        }
        val apk = debugApk.get().asFile
        for (serial in serials) {
            val (code, out) = runCommand(adb, "-s", serial, "install", "-r", apk.absolutePath)
            if (code == 0) {
                logger.lifecycle("deliver: installed on $serial")
            } else {
                logger.warn("deliver: install on $serial failed: $out")
            }
        }
    }
}
