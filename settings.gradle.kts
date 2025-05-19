pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        repositories {
            google()
            mavenCentral()
            // Añade los repositorios de AWS
            maven { url = uri("https://aws.amazon.com/maven/") }
            maven { url = uri("https://s3.amazonaws.com/repo.commonsware.com") }
        }
    }
}

rootProject.name = "locationDevice"
include(":app")
