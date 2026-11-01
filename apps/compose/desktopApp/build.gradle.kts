import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins { kotlin("jvm"); kotlin("plugin.compose"); id("org.jetbrains.compose") }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
}
compose.desktop {
    application {
        mainClass = "org.openbuds.desktop.MainKt"
        providers.gradleProperty("packageJavaHome").orNull?.let { javaHome = it }
        jvmArgs("-XX:+UseSerialGC", "-Xms16m", "-Xmx256m", "--enable-native-access=ALL-UNNAMED",
            "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua")
        buildTypes.release.proguard.configurationFiles.from(project.file("proguard-rules.pro"))
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "OpenBuds"
            packageVersion = "0.4.0"
            modules("java.management", "java.prefs", "jdk.unsupported")
            windows {
                menuGroup = "OpenBuds"; shortcut = true; dirChooser = true
                iconFile.set(project.file("packaging/openbuds.ico"))
            }
            linux { iconFile.set(project.file("src/main/resources/openbuds.png")) }
            // JDK 21 jpackage rejects a zero major on macOS. This is bundle
            // packaging metadata, not a change to the application version (0.4.0).
            macOS { packageVersion = "1.4.0" }
        }
    }
}
val buildRust = tasks.register<Exec>("buildRust") {
    workingDir = rootProject.projectDir.resolve("../..")
    commandLine("cargo", "build", "-p", "realme-buds-ffi", "--lib", "--release", "--locked")
    inputs.files(fileTree("../../../bindings/c/src"), fileTree("../../../crates"))
    inputs.files("../../../Cargo.toml", "../../../Cargo.lock", "../../../bindings/c/Cargo.toml")
    val output = layout.buildDirectory.dir("nativeResources")
    outputs.dir(output)
    doLast {
        val name = when {
            System.getProperty("os.name").startsWith("Windows") -> "openbuds.dll"
            System.getProperty("os.name").startsWith("Mac") -> "libopenbuds.dylib"
            else -> "libopenbuds.so"
        }
        copy {
            from(rootProject.projectDir.resolve("../../target/release/$name"))
            into(output)
        }
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("nativeResources")) }
tasks.named("processResources") { dependsOn(buildRust) }
