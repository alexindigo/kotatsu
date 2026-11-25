pluginManagement {
    resolutionStrategy {
        eachPlugin {
            // The offline cache holds the AGP jar but not the com.android.library marker.
            if (requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kotatsu"
// The release tools read the same manifest rather than parsing Gradle DSL.
val moduleManifest = "gradle/modules.txt"
val moduleRow = Regex("([a-z0-9_-]+(?:/[a-z0-9_-]+)*)=(release|non-release|non-app)")
val moduleClasses = mutableMapOf<String, String>()
file(moduleManifest).readLines().forEachIndexed { index, line ->
    val row = line.trim(' ', '\t')
    if (row.isEmpty() || row.startsWith('#')) return@forEachIndexed
    val (directory, role) = requireNotNull(moduleRow.matchEntire(row)) {
        "$moduleManifest:${index + 1}: invalid module declaration"
    }.destructured
    val projectPath = ":" + directory.replace('/', ':')
    require(moduleClasses.putIfAbsent(projectPath, role) == null) {
        "$moduleManifest:${index + 1}: duplicate Gradle module $directory"
    }
    include(projectPath)
}

gradle.projectsEvaluated {
    val projects = rootProject.subprojects.associateBy { it.path }
    check(projects.keys == moduleClasses.keys) { "Gradle projects must match $moduleManifest" }
    check(rootProject.name == "Kotatsu") { "Wrong Kotatsu root project name" }
    check(!rootProject.pluginManager.hasPlugin("com.android.application")) {
        "The Kotatsu root project must not be an Android application"
    }
    for ((path, project) in projects) {
        val role = moduleClasses.getValue(path)
        val directory = rootProject.projectDir.resolve(path.removePrefix(":").replace(':', '/'))
        check(project.projectDir == directory && project.buildFile.parentFile == directory &&
            project.buildFile.name in setOf("build.gradle.kts", "build.gradle") && project.buildFile.isFile) {
            "$path must use a standard build script in $directory"
        }
        check(project.pluginManager.hasPlugin("com.android.application") == (role != "non-app")) {
            "$path: applied Android application plugin does not match $role in $moduleManifest"
        }
    }
}
