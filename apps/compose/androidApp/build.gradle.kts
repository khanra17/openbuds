plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}
val rustAbis = providers.gradleProperty("rustAbis").orElse("arm64-v8a,armeabi-v7a,x86_64").map { it.split(",").map(String::trim) }
android {
    namespace = "org.openbuds.air7"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = if (providers.gradleProperty("testInstall").orNull == "true") "org.openbuds.air7.test" else "org.openbuds.air7"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"
        ndk { abiFilters += rustAbis.get() }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        create("publicDevelopment") {
            storeFile = file("signing/public-dev.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("publicDevelopment") }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("publicDevelopment")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath)
    packaging { jniLibs.useLegacyPackaging = false }
}
dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
}
val buildRust = tasks.register<Exec>("buildRust") {
    workingDir = rootProject.projectDir.resolve("../..")
    inputs.files(fileTree("../../../bindings/c/src"), fileTree("../../../crates/realme-buds/src"))
    inputs.files("../../../Cargo.toml", "../../../Cargo.lock", "../../../bindings/c/Cargo.toml")
    val output = layout.buildDirectory.dir("rustJniLibs")
    outputs.dir(output)
    inputs.property("abis", rustAbis)
    doFirst {
        require(rustAbis.get().all { it in listOf("arm64-v8a", "armeabi-v7a", "x86_64") })
        project.delete(output.get().asFile)
        val arguments = mutableListOf("cargo", "ndk", "-o", output.get().asFile.absolutePath, "--platform", "24")
        rustAbis.get().forEach { arguments += listOf("-t", it) }
        arguments += listOf("build", "-p", "realme-buds-ffi", "--lib", "--release", "--locked")
        commandLine(arguments)
        environment("ANDROID_NDK_HOME", androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve("ndk/" + android.ndkVersion).absolutePath)
    }
}
tasks.named("preBuild") { dependsOn(buildRust) }
