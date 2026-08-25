// Общее ядро Правки: телефон (:app) и воркстанция (:desktop) собирают из него
// один и тот же движок - промпты, словарь, правила, чистку ответа, вызов
// Claude. Ничего андроидного здесь быть не должно: если понадобился Context -
// значит, место классу в :app, а сюда - интерфейс.
plugins {
    alias(libs.plugins.kotlin.jvm)
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

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    // org.json на Android даёт сама система, поэтому в ядре он только для
    // компиляции; настоящую реализацию подкладывает :desktop.
    compileOnly(libs.json)

    // Тесты гоняют настоящую реализацию org.json - ту самую, что подкладывает
    // воркстанция; на телефоне её роль играет система.
    testImplementation(kotlin("test"))
    testImplementation(libs.json)
}

tasks.test {
    useJUnitPlatform()
}
