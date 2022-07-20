/*
 * Copyright 2022 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.command;

import static com.google.common.base.StandardSystemProperty.USER_HOME;

import com.google.common.io.Files;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.common.experiments.BoolExperiment;
import com.google.idea.common.util.MorePlatformUtils;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.io.File;
import java.io.IOException;

/** Migrate user .blazerc from home directory to workspace root */
public final class BlazercMigrator {
  private static final BoolExperiment enabled =
      new BoolExperiment("blaze.sync.runner.enablebuildapi", true);
  private static final String USER_BLAZERC = ".blazerc";

  public static boolean needMigration(Project project) {
    if (!enabled.getValue() || !MorePlatformUtils.isAndroidStudio()) {
      return false;
    }
    File homeBlazerc = new File(USER_HOME + "/" + USER_BLAZERC);
    File workspaceBlazerc =
        WorkspaceRoot.fromProject(project).fileForPath(new WorkspacePath(USER_BLAZERC));
    return homeBlazerc.exists() && !workspaceBlazerc.exists();
  }

  public static boolean promptMigration(Project project) {
    File workspaceBlazerc =
        WorkspaceRoot.fromProject(project).fileForPath(new WorkspacePath(USER_BLAZERC));
    int response =
        Messages.showYesNoDialog(
            String.format(
                "No .blazerc found at workspace root. Do you want to copy the .blazerc file from \n"
                    + "%s\n"
                    + " to \n"
                    + "%s?",
                USER_HOME, workspaceBlazerc.getPath()),
            "Blaze Configuration",
            null);
    return response == 0;
  }

  public static boolean copyBlazercToWorkspace(Project project) {
    File homeBlazerc = new File(USER_HOME + "/" + USER_BLAZERC);
    File workspaceBlazerc =
        WorkspaceRoot.fromProject(project).fileForPath(new WorkspacePath(USER_BLAZERC));
    try {
      Files.copy(homeBlazerc, workspaceBlazerc);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private BlazercMigrator() {}
}
