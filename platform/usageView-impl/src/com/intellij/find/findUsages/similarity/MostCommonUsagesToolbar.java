// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find.findUsages.similarity;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

import static com.intellij.openapi.actionSystem.ActionPlaces.SIMILAR_USAGES_PREVIEW_TOOLBAR;

class MostCommonUsagesToolbar extends JPanel {
  private final @NotNull SimpleColoredComponent myResultsText;

  MostCommonUsagesToolbar(@NotNull JComponent targetComponent, @Nls String usagesMessage, @NotNull AnAction refreshAction) {
    super(new FlowLayout(FlowLayout.LEFT));
    setBackground(UIUtil.getTextFieldBackground());
    myResultsText = new SimpleColoredComponent();
    updateResultsText(usagesMessage);
    DefaultActionGroup actionGroup = new DefaultActionGroup();
    actionGroup.add(refreshAction);
    ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(SIMILAR_USAGES_PREVIEW_TOOLBAR, actionGroup, true);
    actionToolbar.getComponent().setBackground(UIUtil.getTextFieldBackground());
    actionToolbar.setTargetComponent(targetComponent);
    add(myResultsText);
    add(actionToolbar.getComponent());
  }

  public void updateResultsText(@NotNull @Nls String message) {
    myResultsText.clear();
    myResultsText.append(message, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
  }
}
