// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.service.project.data;

import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.Key;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider;
import com.intellij.openapi.externalSystem.service.project.manage.AbstractModuleDataService;
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings;
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants;
import com.intellij.openapi.externalSystem.util.Order;
import com.intellij.openapi.module.Module;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import static com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil.getSettings;
import static org.jetbrains.plugins.gradle.util.GradleConstants.SYSTEM_ID;

/**
 * @author Vladislav.Soroka
 */
@Order(ExternalSystemConstants.BUILTIN_MODULE_DATA_SERVICE_ORDER + 1)
public final class GradleSourceSetDataService extends AbstractModuleDataService<GradleSourceSetData> {

  @Override
  public @NotNull Key<GradleSourceSetData> getTargetDataKey() {
    return GradleSourceSetData.KEY;
  }

  @Override
  public @NotNull String getExternalModuleType() {
    return GradleConstants.GRADLE_SOURCE_SET_MODULE_TYPE_KEY;
  }

  @Override
  protected @NotNull Module createModule(@NotNull DataNode<GradleSourceSetData> sourceSetModuleNode,
                                         @NotNull IdeModifiableModelsProvider modelsProvider) {
    //noinspection unchecked
    DataNode<ModuleData> parentModuleNode = (DataNode<ModuleData>)sourceSetModuleNode.getParent();
    assert parentModuleNode != null;
    Module parentModule = parentModuleNode.getUserData(MODULE_KEY);
    assert parentModule != null;

    String projectPath = sourceSetModuleNode.getData().getLinkedExternalProjectPath();
    String actualModuleName = modelsProvider.getModifiableModuleModel().getActualName(parentModule);
    ExternalProjectSettings settings = getSettings(parentModule.getProject(), SYSTEM_ID).getLinkedProjectSettings(projectPath);
    if (settings != null && settings.isUseQualifiedModuleNames()) {
      String sourceSetModuleInternalName = sourceSetModuleNode.getData().getInternalName();
      if (!sourceSetModuleInternalName.startsWith(actualModuleName)) {
        String sourceSetName = sourceSetModuleNode.getData().getModuleName();
        String adjustedInternalName = findDeduplicatedModuleName(actualModuleName + "." + sourceSetName, modelsProvider);
        sourceSetModuleNode.getData().setInternalName(adjustedInternalName);
      }
    }
    return super.createModule(sourceSetModuleNode, modelsProvider);
  }

  private static @NotNull String findDeduplicatedModuleName(@NotNull String moduleName,
                                                            @NotNull IdeModifiableModelsProvider modelsProvider) {
    Module ideModule = modelsProvider.findIdeModule(moduleName);
    if (ideModule == null) {
      return moduleName;
    }
    int i = 0;
    while (true) {
      String nextModuleNameCandidate = moduleName + "~" + ++i;
      ideModule = modelsProvider.findIdeModule(nextModuleNameCandidate);
      if (ideModule == null) {
        return nextModuleNameCandidate;
      }
    }
  }
}
