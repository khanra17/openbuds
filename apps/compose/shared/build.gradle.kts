plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
}
kotlin {
    android {
        namespace = "org.openbuds.shared"
        compileSdk = 37
        minSdk = 24
        withHostTestBuilder {}
    }
    jvm("desktop")
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        val jvmMain = create("jvmMain") { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmMain)
        named("desktopMain") { dependsOn(jvmMain) }
        named("desktopTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation("org.jetbrains.compose.ui:ui-test-junit4:1.12.2")
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
            }
        }
        named("androidHostTest") { dependsOn(commonTest.get()) }
    }
}
