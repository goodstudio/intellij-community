// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.externalSystem.statistics

import com.intellij.internal.statistic.beans.MetricEvent
import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EnumEventField
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.ProjectUsagesCollector
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.util.lang.JavaVersion

object ExternalSystemUsageFields {

  private const val UNKNOWN = "unknown"

  val JRE_TYPE_FIELD: EnumEventField<JreType> = EventFields.Enum("value", JreType::class.java) { it.description }

  fun getJreType(jreName: String?): JreType {
    val jreType = JreType.entries.find { it.description == jreName }
    val anonymizedName = when {
      jreName.isNullOrBlank() -> JreType.EMPTY
      jreType != null -> jreType
      else -> JreType.CUSTOM
    }
    return anonymizedName
  }

  fun getJreVersion(project: Project, jreName: String?): String {
    val jdk = try {
      ExternalSystemJdkUtil.getJdk(project, jreName)
    }
    catch (ignored: Throwable) {
      return UNKNOWN
    }
    val javaVersion = JavaVersion.tryParse(jdk?.versionString) ?: return UNKNOWN
    return "${javaVersion.feature}.${javaVersion.minor}"
  }
}

enum class JreType(val description: String) {
  EMPTY("empty"),
  CUSTOM("custom"),
  USE_INTERNAL_JAVA("#JAVA_INTERNAL"),
  USE_PROJECT_JDK("#USE_PROJECT_JDK"),
  USE_JAVA_HOME("#JAVA_HOME")
}

enum class ExternalSystemTaskId {
  ResolveProject,
  ExecuteTask,
}

internal class ExternalSystemUsagesCollector : ProjectUsagesCollector() {
  private val GROUP = EventLogGroup("build.tools", 4)
  private val EXTERNAL_SYSTEM_ID = GROUP.registerEvent("externalSystemId",
                                                       EventFields.StringValidatedByCustomRule<SystemIdValidationRule>("value"))

  override fun getGroup(): EventLogGroup = GROUP

  override fun getMetrics(project: Project): Set<MetricEvent> {
    val usages = mutableSetOf<MetricEvent>()
    for (manager in ExternalSystemApiUtil.getAllManagers()) {
      if (!manager.settingsProvider.`fun`(project).linkedProjectsSettings.isEmpty()) {
        usages.add(EXTERNAL_SYSTEM_ID.metric(getAnonymizedSystemId(manager.systemId)))
      }
    }

    ModuleManager.getInstance(project).modules.find { ExternalSystemModulePropertyManager.getInstance(it).isMavenized() }?.let {
      usages.add(EXTERNAL_SYSTEM_ID.metric("Maven"))
    }
    return usages
  }
}
