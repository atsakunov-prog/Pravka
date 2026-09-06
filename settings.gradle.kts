pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Ветка `slushalka`: здесь живёт только Слушалка. Правка — в ветке `pravka`
// того же репозитория; общие у них keystore и ветка `apk-builds`.
rootProject.name = "Pravka"
include(":slushalka")
