/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */

package com.intellij.openapi.vcs.changes.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vcs.changes.ui.IgnoredSettingsDialog;

/**
 * @author yole
 */
public class IgnoredSettingsAction extends AnAction implements DumbAware {
  public IgnoredSettingsAction() {
    super("Configure Ignored Files...", "Specify file paths and masks which are ignored",
          IconLoader.getIcon("/actions/properties.png"));
  }

  public void actionPerformed(AnActionEvent e) {
    Project project = e.getData(PlatformDataKeys.PROJECT);
    IgnoredSettingsDialog.configure(project);
  }

}