package org.jetbrains.kotlin.tools.projectWizard.wizard.ui.firstStep

import org.jetbrains.kotlin.tools.projectWizard.core.ValuesReadingContext
import org.jetbrains.kotlin.tools.projectWizard.core.entity.SettingReference
import org.jetbrains.kotlin.tools.projectWizard.core.entity.reference
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.KotlinPlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.projectTemplates.ProjectTemplatesPlugin
import org.jetbrains.kotlin.tools.projectWizard.projectTemplates.ProjectTemplate
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.Module
import org.jetbrains.kotlin.tools.projectWizard.wizard.IdeWizard
import org.jetbrains.kotlin.tools.projectWizard.wizard.ui.*
import java.awt.BorderLayout
import javax.swing.JComponent

class FirstWizardStepComponent(wizard: IdeWizard) : WizardStepComponent(wizard.valuesReadingContext) {
    private val buildSystemSubStep = BuildSystemSubStep(wizard.valuesReadingContext).asSubComponent()
    private val templatesSubStep = TemplatesSubStep(wizard.valuesReadingContext).asSubComponent()
    private val kotlinVersionSubStep = KotlinVersionSubstep(wizard.valuesReadingContext).asSubComponent()

    override val component: JComponent = panel {
        add(templatesSubStep.component, BorderLayout.CENTER)
        add(
            panel {
                bordered(needBottomEmptyBorder = false)
                add(buildSystemSubStep.component, BorderLayout.CENTER)
                add(kotlinVersionSubStep.component, BorderLayout.SOUTH)
            },
            BorderLayout.SOUTH
        )
    }
}

class BuildSystemSubStep(valuesReadingContext: ValuesReadingContext) :
    SubStep(valuesReadingContext) {
    private val buildSystemSetting = BuildSystemTypeSettingComponent(valuesReadingContext).asSubComponent()

    override fun buildContent(): JComponent = panel {
        add(buildSystemSetting.component, BorderLayout.CENTER)
    }
}

class KotlinVersionSubstep(valuesReadingContext: ValuesReadingContext) :
    SubStep(valuesReadingContext) {
    private val kotlinVersionSetting = KotlinVersionSettingComponent(valuesReadingContext).asSubComponent()

    private val panel by lazy(LazyThreadSafetyMode.NONE) {
        panel {
            add(kotlinVersionSetting.component, BorderLayout.CENTER)
        }
    }

    override fun onInit() {
        super.onInit()
        val needToBeVisible = read { KotlinPlugin::kotlinVersions.propertyValue.size > 1 }
        panel.isVisible = needToBeVisible
    }

    override fun buildContent(): JComponent = panel
}

class TemplatesSubStep(valuesReadingContext: ValuesReadingContext) :
    SubStep(valuesReadingContext) {
    private val projectTemplateSettingComponent =
        ProjectTemplateSettingComponent(valuesReadingContext) { projectTemplate ->
            templateDescriptionComponent.setTemplate(projectTemplate)
        }.asSubComponent()

    private val templateDescriptionComponent = TemplateDescriptionComponent().asSubComponent()

    override fun buildContent(): JComponent = panel {
        add(projectTemplateSettingComponent.component, BorderLayout.CENTER)
        add(templateDescriptionComponent.component, BorderLayout.SOUTH)
    }

    override fun onInit() {
        super.onInit()
        applySelectedTemplate()
    }

    private fun applySelectedTemplate() {
        projectTemplateSettingComponent.value?.setsValues?.forEach { (setting, value) ->
            // TODO do not use settingContext directly
            context.settingContext[setting] = value
        }
        allModules().forEach { module ->
            module.initDefaultValuesForSettings(valuesReadingContext.context)
        }
    }

    private fun allModules(): List<Module> {
        val modules = mutableListOf<Module>()

        fun addModule(module: Module) {
            modules += module
            module.subModules.forEach(::addModule)
        }

        valuesReadingContext.context.settingContext[KotlinPlugin::modules.reference]
            ?.forEach(::addModule)

        return modules
    }

    override fun onValueUpdated(reference: SettingReference<*, *>?) {
        super.onValueUpdated(reference)
        if (reference == ProjectTemplatesPlugin::template.reference) {
            applySelectedTemplate()
        }
    }
}

class TemplateDescriptionComponent : Component() {
    private val descriptionPanel = DescriptionPanel()

    fun setTemplate(template: ProjectTemplate) {
        descriptionPanel.updateText(template.htmlDescription)
    }

    override val component: JComponent = panel {
        bordered()
        add(descriptionPanel, BorderLayout.CENTER)
    }
}