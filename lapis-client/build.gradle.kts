plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kvision)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "main.bundle.js"
            }
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        named("jsMain") {
            dependencies {
                implementation(project(":lapis-shared"))
                implementation(libs.kvision.core)
                implementation(libs.kvision.bootstrap)
            }
        }
    }
}
