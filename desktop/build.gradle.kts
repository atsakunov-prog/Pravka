import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Правка на воркстанции: тот же движок, что на телефоне (:core), но вход -
// горячая клавиша, распознавание - локальный Whisper (scripts/whisper), а
// текст уезжает в поле чужого приложения через буфер обмена.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Заводской словарь один на оба устройства: файл лежит в assets телефона и
// приезжает сюда на сборке, чтобы не жить в репозитории двумя копиями.
sourceSets["main"].resources.srcDir(rootProject.file("app/src/main/assets"))

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    // На Android org.json даёт система, здесь его надо принести с собой.
    implementation(libs.json)
    // Глобальные горячие клавиши: обычный слушатель Swing видит только своё
    // окно, а нажатие ловить надо в чужом.
    implementation(libs.jnativehook)
}

compose.desktop {
    application {
        mainClass = "ru.zf.pravka.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Pravka"
            packageVersion = "2.0.0"
            description = "Правка: диктовка и причёсывание текста"
            vendor = "ЗФ"
            windows {
                menu = true
                // Стабильный UPGRADE_UUID: следующая версия ставится поверх,
                // а не второй копией.
                upgradeUuid = "8f1c0b3e-6f4a-4a2b-9f7d-2c5e1d0a9b34"
            }
        }
    }
}

