import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Слушалка ships as its OWN app (own applicationId, own launcher icon, own
// data) - it just lives in the Правка repo so the two share a keystore, a
// version counter and one CI run.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, default: String? = null): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: default

// Счётчик версий читается, но не двигается: номер сборки задаёт CI
// (-PbuildNumber = номер запуска workflow, общий на репозиторий), а локальная
// сборка просто берёт то, что записано.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val buildNumber: Int = (project.findProperty("buildNumber") as? String)?.toIntOrNull()
    ?: versionProps.getProperty("buildNumber", "1").trim().toInt()
/** Android-клиент Google Слушалки в проекте семейного аккаунта; пусто — вход не работает. */
val GOOGLE_CLIENT_ID = ""
val buildTimestamp: String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(ZonedDateTime.now())

android {
    namespace = "ru.zf.slushalka"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.zf.slushalka"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.1.$buildNumber"
        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")
        // Клиент Google (тип Android: пакет ru.zf.slushalka и подпись pravka.jks)
        // для входа в семейный Drive — data/GoogleAuth.kt. ID не секрет: Google
        // показывает его в каждой ссылке входа; секрета у Android-клиента нет.
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${prop("google.clientId", GOOGLE_CLIENT_ID)}\"")
    }

    // The same shared keystore as Правка: a different key would break
    // install-over-update and wipe the listening positions with it.
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    // Только для проверок на машине: в APK не попадает. Нужен типографу -
    // у него есть свойство, которое нельзя сломать молча (длина текста).
    testImplementation("junit:junit:4.13.2")
    // org.json в android.jar - заглушки без тела: разбор ответов модели и
    // справочника на машине проверяется настоящей библиотекой. В APK не едет.
    testImplementation("org.json:json:20240303")
}
