// ============================================================================
// SETTINGS.GRADLE.KTS - BRAINSTORMIA v9.9
// ============================================================================
// 📁 Arquivo: settings.gradle.kts
// 🎯 Objetivo: Configuração de repositórios e módulos
// 🔄 Atualizado: 2025-05-29
// ============================================================================

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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "StormChat"
include(":app")