pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "Lapis-Cloud"

// ── Shared (KMP: jvm + js) ───────────────────────────────────────
include("lapis-shared") // Shared DTOs/domain code used by both server and client

// ── Server (Ktor) ─────────────────────────────────────────────────
include("lapis-server") // Ktor application

// ── Client (KVision, Kotlin/JS) ────────────────────────────────────
include("lapis-client") // KVision UI, Kotlin/JS
