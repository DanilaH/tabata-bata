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
        maven {
            url = uri("https://nexus-external.rustore.ru/repository/maven-rustore-exposed")
            content { includeGroup("ru.rustore.sdk") }
        }
    }
}

rootProject.name = "TabataBata"

include(":app")
