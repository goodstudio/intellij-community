// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.gradle.scripting

import org.jetbrains.kotlin.scripting.definitions.ScriptCompilationConfigurationFromDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.KotlinScriptDefinitionFromAnnotatedTemplate
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.ScriptingHostConfiguration

class GradleKotlinScriptDefinitionWrapper(
    hostConfiguration: ScriptingHostConfiguration,
    legacyDefinition: KotlinScriptDefinitionFromAnnotatedTemplate,
    gradleVersion: String,
    defaultCompilerOptions: Iterable<String>
) : ScriptDefinition.FromLegacy(hostConfiguration, legacyDefinition, defaultCompilerOptions) {
    override val compilationConfiguration by lazy {
        ScriptCompilationConfigurationFromDefinition(
            hostConfiguration,
            legacyDefinition
        ).with {
            ScriptCompilationConfiguration.ide.acceptedLocations.put(listOf(ScriptAcceptedLocation.Project))
        }
    }

    override val canDefinitionBeSwitchedOff: Boolean = false
}