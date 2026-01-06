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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "YoPlayer"

// Media3 settings
gradle.extra["androidxMediaSettingsDir"] = rootDir.absolutePath
gradle.extra["androidxMediaModulePrefix"] = ""

// Media3 library modules
include(":lib-common")
project(":lib-common").projectDir = file("libraries/common")

include(":lib-container")
project(":lib-container").projectDir = file("libraries/container")

include(":lib-database")
project(":lib-database").projectDir = file("libraries/database")

include(":lib-datasource")
project(":lib-datasource").projectDir = file("libraries/datasource")

include(":lib-decoder")
project(":lib-decoder").projectDir = file("libraries/decoder")

include(":lib-decoder-ffmpeg")
project(":lib-decoder-ffmpeg").projectDir = file("libraries/decoder_ffmpeg")

include(":lib-extractor")
project(":lib-extractor").projectDir = file("libraries/extractor")

include(":lib-exoplayer")
project(":lib-exoplayer").projectDir = file("libraries/exoplayer")

// YoPlayer SDK - Custom module (Kotlin)
include(":yoplayersdk")
project(":yoplayersdk").projectDir = file("yoplayersdk")

include(":app")
 