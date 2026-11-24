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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // Termux terminal-emulator / terminal-view (GPLv3) are published through JitPack.
    // Limited to the Termux group so no other dependency can be resolved from JitPack.
    maven {
      url = uri("https://jitpack.io")
      content { includeGroup("com.github.termux.termux-app") }
    }
  }
}

rootProject.name = "Cyberorg"

include(":app")
