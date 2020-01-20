package org.jetbrains.kotlin.tools.projectWizard.plugins.buildSystem

import org.jetbrains.kotlin.tools.projectWizard.core.*
import org.jetbrains.kotlin.tools.projectWizard.core.entity.ValidationResult
import org.jetbrains.kotlin.tools.projectWizard.core.entity.reference
import org.jetbrains.kotlin.tools.projectWizard.core.service.BuildSystemAvailabilityWizardService
import org.jetbrains.kotlin.tools.projectWizard.core.service.FileSystemWizardService
import org.jetbrains.kotlin.tools.projectWizard.core.service.ProjectImportingWizardService
import org.jetbrains.kotlin.tools.projectWizard.ir.buildsystem.*
import org.jetbrains.kotlin.tools.projectWizard.library.MavenArtifact
import org.jetbrains.kotlin.tools.projectWizard.phases.GenerationPhase
import org.jetbrains.kotlin.tools.projectWizard.plugins.StructurePlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.KotlinPlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.ProjectKind
import org.jetbrains.kotlin.tools.projectWizard.plugins.printer.BuildFilePrinter
import org.jetbrains.kotlin.tools.projectWizard.plugins.printer.printBuildFile
import org.jetbrains.kotlin.tools.projectWizard.plugins.projectPath
import org.jetbrains.kotlin.tools.projectWizard.settings.DisplayableSettingItem
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.updateBuildFiles
import java.nio.file.Path

abstract class BuildSystemPlugin(context: Context) : Plugin(context) {
    val type by enumSetting<BuildSystemType>("Build System", GenerationPhase.FIRST_STEP) {
        filter = { _, type ->
            val service = service<BuildSystemAvailabilityWizardService>()!!
            service.isAvailable(type)
        }

        validate { buildSystemType ->
            if (!buildSystemType.isGradle
                && KotlinPlugin::projectKind.reference.notRequiredSettingValue == ProjectKind.Multiplatform
            ) {
                ValidationResult.ValidationError("Multiplatform project cannot be generated using ${buildSystemType.text}")
            } else ValidationResult.OK
        }
    }

    val buildSystemData by property<List<BuildSystemData>>(emptyList())

    val buildFiles by listProperty<BuildFileIR>()
    val extraBuildFiles by property<Map<Path, BuildFileLikeIR>>(emptyMap())

    val takeRepositoriesFromDependencies by pipelineTask(GenerationPhase.PROJECT_GENERATION) {
        runBefore(BuildSystemPlugin::createModules)
        runAfter(KotlinPlugin::createModules)

        withAction {
            updateBuildFiles { buildFile ->
                val dependenciesOfModule = buildList<LibraryDependencyIR> {
                    buildFile.modules.modules.forEach { module ->
                        if (module is SingleplatformModuleIR) module.sourcesets.forEach { sourceset ->
                            +sourceset.irs.filterIsInstance<LibraryDependencyIR>()
                        }
                        +module.irs.filterIsInstance<LibraryDependencyIR>()
                    }
                }
                val repositoriesToAdd = dependenciesOfModule.mapNotNull { dependency ->
                    dependency.artifact.safeAs<MavenArtifact>()?.repository?.let(::RepositoryIR)
                }
                buildFile.withIrs(repositoriesToAdd).asSuccess()
            }
        }
    }

    val createModules by pipelineTask(GenerationPhase.PROJECT_GENERATION) {
        runAfter(StructurePlugin::createProjectDir)
        withAction {
            val fileSystem = service<FileSystemWizardService>()!!
            val data = BuildSystemPlugin::buildSystemData.propertyValue.first { it.type == buildSystemType }
            val buildFileData = data.buildFileData ?: return@withAction UNIT_SUCCESS
            BuildSystemPlugin::buildFiles.propertyValue.mapSequenceIgnore { buildFile ->
                fileSystem.createFile(
                    buildFile.directoryPath / buildFileData.buildFileName,
                    buildFileData.createPrinter().printBuildFile { buildFile.render(this) }
                )
            } andThen BuildSystemPlugin::extraBuildFiles.propertyValue.toList().mapSequenceIgnore { (path, extraBuildFile) ->
                fileSystem.createFile(
                    path,
                    buildFileData.createPrinter().printBuildFile { extraBuildFile.render(this) }
                )
            }
        }
    }

    val importProject by pipelineTask(GenerationPhase.PROJECT_IMPORT) {
        runAfter(BuildSystemPlugin::createModules)
        withAction {
            val data = BuildSystemPlugin::buildSystemData.propertyValue.first { it.type == buildSystemType }
            service<ProjectImportingWizardService> { service -> service.isSuitableFor(data.type) }!!
                .importProject(StructurePlugin::projectPath.reference.settingValue, allModules)
        }
    }

    protected fun addBuildSystemData(data: BuildSystemData) = pipelineTask(GenerationPhase.PREPARE) {
        runBefore(BuildSystemPlugin::createModules)
        activityChecker = Checker.ALWAYS_AVAILABLE
        withAction {
            BuildSystemPlugin::buildSystemData.addValues(data)
        }
    }
}

inline fun <reified B : BuildFileLikeIR> TaskRunningContext.updateBuildFileIROfTypeFor(
    buildFileIR: BuildFileIR,
    filename: String,
    crossinline updater: (B?) -> B
) {
    BuildSystemPlugin::extraBuildFiles.update { extraBuildFiles ->
        val path = buildFileIR.directoryPath / filename
        Success(extraBuildFiles + (path to updater(extraBuildFiles[path] as? B)))
    }
}

data class BuildSystemData(
    val type: BuildSystemType,
    val buildFileData: BuildFileData?
)

data class BuildFileData(
    val createPrinter: () -> BuildFilePrinter,
    val buildFileName: String
)

enum class BuildSystemType(override val text: String) : DisplayableSettingItem {
    GradleKotlinDsl("Gradle (Kotlin DSL)"),
    GradleGroovyDsl("Gradle (Groovy DSL)"),
    Jps("IDEA Build System"),
    Maven("Maven")

    ;

    override val greyText: String?
        get() = null
}

val BuildSystemType.isGradle
    get() = this == BuildSystemType.GradleGroovyDsl
            || this == BuildSystemType.GradleKotlinDsl

val TaskRunningContext.allModules
    get() = BuildSystemPlugin::buildFiles.propertyValue.flatMap { buildFile ->
        buildFile.modules.modules
    }

val TaskRunningContext.allModulesPaths
    get() = BuildSystemPlugin::buildFiles.propertyValue.flatMap { buildFile ->
        val paths = when (val structure = buildFile.modules) {
            is MultiplatformModulesStructureIR -> listOf(buildFile.directoryPath)
            else -> structure.modules.map { it.path }
        }
        paths.mapNotNull { path ->
            projectPath.relativize(path)
                .takeIf { it.toString().isNotBlank() }
                ?.toList()
                ?.takeIf { it.isNotEmpty() }
        }
    }


val TaskRunningContext.buildSystemType: BuildSystemType
    get() = BuildSystemPlugin::type.reference.settingValue

