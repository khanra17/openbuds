plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("com.android.application") version "9.4.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.4.1" apply false
}

// Build-time license inventory only; absolute cache paths are never distributed.
subprojects {
    if (name == "desktopApp" || name == "androidApp") tasks.register("writeRuntimeMetadata") {
        val output = layout.buildDirectory.file("runtime-artifacts.tsv")
        outputs.file(output)
        outputs.upToDateWhen { false }
        notCompatibleWithConfigurationCache("Resolved runtime license inventory")
        doLast {
            val runtime = configurations.getByName(if (project.name == "desktopApp") "runtimeClasspath" else "releaseRuntimeClasspath")
            val modules = runtime.incoming.artifactView {
                componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            }.artifacts.artifacts
            val lines = modules.sortedBy { it.id.componentIdentifier.toString() }.map { artifact ->
                val id = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
                listOf(id.group, id.module, id.version, artifact.file.absolutePath).joinToString("\t")
            }
            output.get().asFile.writeText(lines.joinToString("\n") + "\n")
        }
    }
}
