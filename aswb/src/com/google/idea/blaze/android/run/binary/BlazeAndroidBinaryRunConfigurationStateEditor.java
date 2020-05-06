/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.android.run.binary;

import static com.google.idea.blaze.android.run.binary.BlazeAndroidBinaryRunConfigurationHandler.MI_NEVER_ASK_AGAIN;

import com.android.tools.idea.run.activity.ActivityLocatorUtils;
import com.android.tools.idea.run.editor.AndroidProfilersPanelCompat;
import com.google.common.collect.Sets;
import com.google.idea.blaze.android.manifest.ManifestParser;
import com.google.idea.blaze.android.manifest.ParsedManifestService;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.AndroidResourceModuleRegistry;
import com.google.idea.blaze.base.command.buildresult.OutputArtifactResolver;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.run.state.RunConfigurationState;
import com.google.idea.blaze.base.run.state.RunConfigurationStateEditor;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.google.idea.blaze.base.ui.ComboWrapper;
import com.google.idea.blaze.base.ui.IntegerTextField;
import com.google.idea.blaze.base.ui.UiUtil;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.util.TreeClassChooser;
import com.intellij.ide.util.TreeClassChooserFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComponentWithBrowseButton;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.LanguageTextField;
import com.intellij.ui.components.JBTabbedPane;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.NavigableSet;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;

/** An editor for android binary run configs. */
class BlazeAndroidBinaryRunConfigurationStateEditor implements RunConfigurationStateEditor {
  private final RunConfigurationStateEditor commonStateEditor;
  private final AndroidProfilersPanelCompat profilersPanelCompat;

  private Box mainContainer;
  private ComponentWithBrowseButton<EditorTextField> activityField;
  private ComboWrapper<String> watchFaceCombo;
  private JRadioButton launchNothingButton;
  private JRadioButton launchDefaultButton;
  private JRadioButton launchCustomButton;
  private JRadioButton launchWatchFaceButton;
  private JCheckBox useMobileInstallCheckBox;
  private JCheckBox useWorkProfileIfPresentCheckBox;
  private JCheckBox showLogcatAutomaticallyCheckBox;
  private JLabel userIdLabel;
  private IntegerTextField userIdField;

  private boolean componentEnabled = true;
  private boolean hasWatchFaces = false;

  BlazeAndroidBinaryRunConfigurationStateEditor(
      RunConfigurationStateEditor commonStateEditor,
      AndroidProfilersPanelCompat profilersPanelCompat,
      Project project) {
    this.commonStateEditor = commonStateEditor;
    this.profilersPanelCompat = profilersPanelCompat;

    NavigableSet<String> watchFaces = getManifestWatchFaces(project);
    hasWatchFaces = !watchFaces.isEmpty();

    setupUI(project);
    userIdField.setMinValue(0);

    activityField.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            if (!project.isInitialized()) {
              return;
            }
            // We find all Activity classes in the module for the selected variant
            // (or any of its deps).
            final JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            PsiClass activityBaseClass =
                facade.findClass(
                    AndroidUtils.ACTIVITY_BASE_CLASS_NAME, ProjectScope.getAllScope(project));
            if (activityBaseClass == null) {
              Messages.showErrorDialog(
                  mainContainer, AndroidBundle.message("cant.find.activity.class.error"));
              return;
            }
            GlobalSearchScope searchScope = GlobalSearchScope.projectScope(project);
            PsiClass initialSelection =
                facade.findClass(activityField.getChildComponent().getText(), searchScope);
            TreeClassChooser chooser =
                TreeClassChooserFactory.getInstance(project)
                    .createInheritanceClassChooser(
                        "Select Activity Class",
                        searchScope,
                        activityBaseClass,
                        initialSelection,
                        null);
            chooser.showDialog();
            PsiClass selClass = chooser.getSelected();
            if (selClass != null) {
              // This must be done because Android represents
              // inner static class paths differently than java.
              String qualifiedActivityName =
                  ActivityLocatorUtils.getQualifiedActivityName(selClass);
              activityField.getChildComponent().setText(qualifiedActivityName);
            }
          }
        });

    if (hasWatchFaces) {
      watchFaceCombo.setItems(watchFaces);
    }

    ActionListener listener = e -> updateEnabledState();
    if (hasWatchFaces) {
      launchWatchFaceButton.addActionListener(listener);
    }
    launchCustomButton.addActionListener(listener);
    launchDefaultButton.addActionListener(listener);
    launchNothingButton.addActionListener(listener);

    useMobileInstallCheckBox.addActionListener(
        e -> PropertiesComponent.getInstance(project).setValue(MI_NEVER_ASK_AGAIN, true));

    useWorkProfileIfPresentCheckBox.addActionListener(listener);
  }

  @NotNull
  private NavigableSet<String> getManifestWatchFaces(Project project) {
    NavigableSet<String> watchFaces = Sets.newTreeSet();
    BlazeProjectData blazeProjectData =
        BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
    if (blazeProjectData == null) {
      return watchFaces;
    }
    TargetMap targetMap = blazeProjectData.getTargetMap();
    ArtifactLocationDecoder decoder = blazeProjectData.getArtifactLocationDecoder();
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      AndroidResourceModule resourceModule =
          AndroidResourceModuleRegistry.getInstance(project).get(module);
      if (resourceModule == null) {
        continue;
      }
      TargetIdeInfo target = targetMap.get(resourceModule.targetKey);
      if (target == null) {
        continue;
      }
      if (target.getAndroidIdeInfo() == null || target.getAndroidIdeInfo().getManifest() == null) {
        continue;
      }
      ArtifactLocation manifestArtifactLocation = target.getAndroidIdeInfo().getManifest();

      File manifestFile =
          OutputArtifactResolver.resolve(project, decoder, manifestArtifactLocation);
      if (manifestFile == null) {
        continue;
      }
      try {
        ManifestParser.ParsedManifest manifest =
            ParsedManifestService.getInstance(project).getParsedManifest(manifestFile);
        if (manifest != null) {
          watchFaces.addAll(manifest.watchFaceClassNames);
        }
      } catch (IOException ignored) {
        // Do nothing
      }
    }

    return watchFaces;
  }

  @Override
  public void resetEditorFrom(RunConfigurationState genericState) {
    BlazeAndroidBinaryRunConfigurationState state =
        (BlazeAndroidBinaryRunConfigurationState) genericState;
    commonStateEditor.resetEditorFrom(state.getCommonState());
    profilersPanelCompat.resetFrom(state.getProfilerState());
    boolean launchSpecificActivity =
        state.getMode().equals(BlazeAndroidBinaryRunConfigurationState.LAUNCH_SPECIFIC_ACTIVITY);
    boolean launchSpecificWatchFace =
        hasWatchFaces
            && state
                .getMode()
                .equals(BlazeAndroidBinaryRunConfigurationState.LAUNCH_SPECIFIC_WATCH_FACE);
    if (state.getMode().equals(BlazeAndroidBinaryRunConfigurationState.LAUNCH_DEFAULT_ACTIVITY)) {
      launchDefaultButton.setSelected(true);
    } else if (launchSpecificActivity) {
      launchCustomButton.setSelected(true);
    } else if (launchSpecificWatchFace) {
      launchWatchFaceButton.setSelected(true);
    } else {
      launchNothingButton.setSelected(true);
    }
    if (launchSpecificActivity) {
      activityField.getChildComponent().setText(state.getActivityClass());
    }
    if (launchSpecificWatchFace) {
      watchFaceCombo.setSelectedItem(state.getWatchFaceClass());
    }

    useMobileInstallCheckBox.setSelected(
        AndroidBinaryLaunchMethodsUtils.useMobileInstall(state.getLaunchMethod()));
    useWorkProfileIfPresentCheckBox.setSelected(state.useWorkProfileIfPresent());
    userIdField.setValue(state.getUserId());

    showLogcatAutomaticallyCheckBox.setSelected(state.showLogcatAutomatically());

    updateEnabledState();
  }

  @Override
  public void applyEditorTo(RunConfigurationState genericState) {
    BlazeAndroidBinaryRunConfigurationState state =
        (BlazeAndroidBinaryRunConfigurationState) genericState;
    commonStateEditor.applyEditorTo(state.getCommonState());
    profilersPanelCompat.applyTo(state.getProfilerState());

    state.setUserId((Integer) userIdField.getValue());
    if (launchDefaultButton.isSelected()) {
      state.setMode(BlazeAndroidBinaryRunConfigurationState.LAUNCH_DEFAULT_ACTIVITY);
    } else if (launchCustomButton.isSelected()) {
      state.setMode(BlazeAndroidBinaryRunConfigurationState.LAUNCH_SPECIFIC_ACTIVITY);
      state.setActivityClass(activityField.getChildComponent().getText());
    } else if (hasWatchFaces && launchWatchFaceButton.isSelected()) {
      state.setMode(BlazeAndroidBinaryRunConfigurationState.LAUNCH_SPECIFIC_WATCH_FACE);
      state.setWatchFaceClass(watchFaceCombo.getSelectedItem());
    } else {
      state.setMode(BlazeAndroidBinaryRunConfigurationState.DO_NOTHING);
    }
    state.setLaunchMethod(
        AndroidBinaryLaunchMethodsUtils.getLaunchMethod(useMobileInstallCheckBox.isSelected()));
    state.setUseWorkProfileIfPresent(useWorkProfileIfPresentCheckBox.isSelected());
    state.setShowLogcatAutomatically(showLogcatAutomaticallyCheckBox.isSelected());
  }

  @Override
  public JComponent createComponent() {
    // old
    // return UiUtil.createBox(commonStateEditor.createComponent(), mainContainer);
    JBTabbedPane tabbedPane = new JBTabbedPane();
    JComponent generalPanel = UiUtil.createBox(commonStateEditor.createComponent(), mainContainer);
    generalPanel.setOpaque(true);
    tabbedPane.addTab("General", generalPanel);
    if (profilersPanelCompat.getPanel() != null) {
      tabbedPane.addTab("Profiler", profilersPanelCompat.getPanel().getComponent());
    }
    return UiUtil.createBox(tabbedPane);
  }

  private void updateEnabledState() {
    boolean useWorkProfile = useWorkProfileIfPresentCheckBox.isSelected();
    userIdLabel.setEnabled(componentEnabled && !useWorkProfile);
    userIdField.setEnabled(componentEnabled && !useWorkProfile);
    commonStateEditor.setComponentEnabled(componentEnabled);
    activityField.setEnabled(componentEnabled && launchCustomButton.isSelected());
    if (hasWatchFaces) {
      watchFaceCombo.getCombo().setEnabled(componentEnabled && launchWatchFaceButton.isSelected());
      launchWatchFaceButton.setEnabled(componentEnabled);
    }
    launchNothingButton.setEnabled(componentEnabled);
    launchDefaultButton.setEnabled(componentEnabled);
    launchCustomButton.setEnabled(componentEnabled);
    useMobileInstallCheckBox.setEnabled(componentEnabled);
    useWorkProfileIfPresentCheckBox.setEnabled(componentEnabled);
    showLogcatAutomaticallyCheckBox.setEnabled(componentEnabled);
  }

  @Override
  public void setComponentEnabled(boolean enabled) {
    componentEnabled = enabled;
    updateEnabledState();
  }

  /** Create UI components. */
  private void setupUI(Project project) {
    // Mobile install settings
    useMobileInstallCheckBox = new JCheckBox();
    useMobileInstallCheckBox.setText("Use mobile-install");
    useMobileInstallCheckBox.setSelected(true);

    // User settings
    useWorkProfileIfPresentCheckBox = new JCheckBox();
    useWorkProfileIfPresentCheckBox.setText(" Use work profile if present");
    userIdLabel = new JLabel();
    userIdLabel.setText("User ID:");
    userIdField = new IntegerTextField();
    Box userBox =
        UiUtil.createBox(
            useWorkProfileIfPresentCheckBox,
            UiUtil.createHorizontalBox(1, userIdLabel, userIdField));
    userBox.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "User"));

    // Log cat
    showLogcatAutomaticallyCheckBox = new JCheckBox(" Show logcat automatically");
    Box logcatBox =
        UiUtil.createHorizontalBox(0, showLogcatAutomaticallyCheckBox, Box.createHorizontalGlue());
    logcatBox.setBorder(
        BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Logcat"));

    // Activity launch options
    launchNothingButton = new JRadioButton();
    launchNothingButton.setText("Do not launch Activity");

    launchDefaultButton = new JRadioButton();
    launchDefaultButton.setText("Launch default Activity");
    launchDefaultButton.setMnemonic('L');
    launchDefaultButton.setDisplayedMnemonicIndex(0);

    launchCustomButton = new JRadioButton();
    launchCustomButton.setText("Launch:");
    launchCustomButton.setMnemonic('A');
    launchCustomButton.setDisplayedMnemonicIndex(1);

    if (hasWatchFaces) {
      launchWatchFaceButton = new JRadioButton();
      launchWatchFaceButton.setText("Watch face:");
      launchWatchFaceButton.setMnemonic('W');
      launchWatchFaceButton.setDisplayedMnemonicIndex(0);
    }

    final EditorTextField editorTextField =
        new LanguageTextField(PlainTextLanguage.INSTANCE, project, "") {
          @Override
          protected EditorEx createEditor() {
            final EditorEx editor = super.createEditor();
            final PsiFile file =
                PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());

            if (file != null) {
              DaemonCodeAnalyzer.getInstance(project).setHighlightingEnabled(file, false);
            }
            editor.putUserData(
                Key.create("BlazeActivityClassTextField"),
                BlazeAndroidBinaryRunConfigurationStateEditor.this);
            return editor;
          }
        };
    activityField = new ComponentWithBrowseButton<>(editorTextField, null);

    ButtonGroup buttonGroup;
    buttonGroup = new ButtonGroup();
    buttonGroup.add(launchDefaultButton);
    buttonGroup.add(launchCustomButton);
    buttonGroup.add(launchNothingButton);

    Box activityBox =
        UiUtil.createBox(
            launchNothingButton,
            launchDefaultButton,
            UiUtil.createHorizontalBox(0, launchCustomButton, activityField));
    activityBox.setBorder(
        BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Activity"));

    if (hasWatchFaces) {
      buttonGroup.add(launchWatchFaceButton);
      watchFaceCombo = ComboWrapper.create();
      Box watchFaceBox =
          UiUtil.createHorizontalBox(0, launchWatchFaceButton, watchFaceCombo.getCombo());
      watchFaceBox.setAlignmentX(0);
      activityBox.add(watchFaceBox);
    }

    // Panel to hold all the above editable components.
    mainContainer = UiUtil.createBox(useMobileInstallCheckBox, activityBox, userBox, logcatBox);
  }
}
