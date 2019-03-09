/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.android.project.api;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.swing.Timer;
import nbandroid.gradle.spi.BuildMutex;
import nbandroid.gradle.spi.BuildMutexProvider;
import nbandroid.gradle.spi.GradleArgsConfiguration;
import nbandroid.gradle.spi.GradleCommandExecutor;
import nbandroid.gradle.spi.GradleCommandExecutorProvider;
import nbandroid.gradle.spi.GradleHandler;
import nbandroid.gradle.spi.GradleJvmConfiguration;
import nbandroid.gradle.spi.ModelRefresh;
import org.nbandroid.netbeans.gradle.v2.sdk.AndroidSdk;
import org.nbandroid.netbeans.gradle.v2.sdk.AndroidSdkImpl;
import org.nbandroid.netbeans.gradle.v2.sdk.AndroidSdkProvider;
import org.nbandroid.netbeans.gradle.v2.sdk.AndroidSdkTools;
import org.nbandroid.netbeans.gradle.v2.sdk.PlatformConvertor;
import org.nbandroid.netbeans.gradle.v2.sdk.ui.SdksCustomizer;
import org.netbeans.api.io.IOProvider;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.modules.android.project.properties.AndroidProjectPropsImpl;
import org.netbeans.modules.android.project.properties.AuxiliaryConfigImpl;
import org.netbeans.spi.project.AuxiliaryConfiguration;
import org.netbeans.spi.project.AuxiliaryProperties;
import org.netbeans.spi.project.ProjectState;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.filesystems.FileAttributeEvent;
import org.openide.filesystems.FileChangeListener;
import org.openide.filesystems.FileEvent;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileRenameEvent;
import org.openide.filesystems.FileUtil;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.LookupEvent;
import org.openide.util.LookupListener;
import org.openide.util.RequestProcessor;
import org.openide.util.WeakListeners;
import org.openide.util.lookup.AbstractLookup;
import org.openide.util.lookup.InstanceContent;
import org.openide.util.lookup.ProxyLookup;

/**
 *
 * @author arsi
 */
public abstract class NbAndroidProject implements Project, LookupListener, Runnable, FileChangeListener {

    public static final String EXTENSION_NAME = "org.nbandroid.netbeans.gradle.v2.AndroidExtensionDef";
    public static final String LOCAL_PROPERTIES = "local.properties";
    public static final String GRADLE_PROPERTIES = "gradle.properties";
    public static final String BUILD_GRADLE = "build.gradle";
    public static final String SDK_DIR = "sdk.dir";
    public static final String COMMENT = "## This file is automatically generated by Apache NetBeans.\n"
            + "# Do not modify this file -- YOUR CHANGES WILL BE ERASED!\n"
            + "#\n"
            + "# This file must *NOT* be checked into Version Control Systems,\n"
            + "# as it contains information specific to your local configuration.\n"
            + "#\n"
            + "# Location of the SDK. This is only used by Gradle.\n"
            + "# For customization when using a Version Control System, please read the\n"
            + "# header note.\n"
            + "#DATE";

    protected final FileObject projectDirectory;
    protected final InstanceContent ic = new InstanceContent();
    protected final InstanceContent icModels = new InstanceContent();
    protected final AbstractLookup lookup = new AbstractLookup(ic);
    protected final AbstractLookup lookupModels = new AbstractLookup(icModels);
    protected final Lookup proxyLookup = new ProxyLookup(lookup, lookupModels);
    protected final Lookup modelLookup;
    protected final Lookup.Result<Object> modelLookupResult;
    public static final RequestProcessor RP = new RequestProcessor(NbAndroidProject.class.getName(), Runtime.getRuntime().availableProcessors());

    protected final Object lock = new Object();
    private AndroidSdk projectSdk;
    private FileObject localPropertiesFo;
    private final Timer localPropertiesChangeTimer;
    protected final AuxiliaryConfiguration auxiliaryConfig = new AuxiliaryConfigImpl(this);
    protected final AuxiliaryProperties auxiliaryProperties = new AndroidProjectPropsImpl(auxiliaryConfig);
    protected final CopyOnWriteArrayList lastModels = new CopyOnWriteArrayList();
    protected final BuildMutex buildMutex = Lookup.getDefault().lookup(BuildMutexProvider.class).create();
    protected final GradleCommandExecutor gradleCommandExecutor;
    protected final GradleJvmConfiguration gradleJvmConfiguration;
    protected final GradleArgsConfiguration gradleArgsConfiguration;

    public NbAndroidProject(FileObject projectDirectory, ProjectState ps) {
        this.projectDirectory = projectDirectory;
        ic.add(buildMutex);
        ic.add(this);
        ic.add(auxiliaryConfig);
        ic.add(auxiliaryProperties);
        gradleJvmConfiguration = new GradleJvmConfiguration(this);
        gradleArgsConfiguration = new GradleArgsConfiguration(this);
        ic.add(gradleJvmConfiguration);
        ic.add(gradleArgsConfiguration);
        modelLookup = GradleHandler.getDefault().getModelLookup(this);
        modelLookupResult = modelLookup.lookupResult(Object.class);
        modelLookupResult.addLookupListener(this);
        ic.add(IOProvider.get("Terminal").getIO(projectDirectory.getName(), false));
        ic.add(new ModelRefresh() {
            @Override
            public void refreshModels() {
                RP.execute(NbAndroidProject.this);
            }
        });
        initSdk(projectDirectory);
        localPropertiesChangeTimer = new Timer(1000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshSdk();
            }
        });
        localPropertiesChangeTimer.setRepeats(false);
        if (projectSdk != null) {
            ic.add(projectSdk);
            localPropertiesFo.addFileChangeListener(WeakListeners.create(FileChangeListener.class, this, localPropertiesFo));
        }
        gradleCommandExecutor = Lookup.getDefault().lookup(GradleCommandExecutorProvider.class).create(this);
        ic.add(gradleCommandExecutor);
        RP.execute(this);
    }

    @Override
    public FileObject getProjectDirectory() {
        return projectDirectory;
    }

    public AuxiliaryConfiguration getAuxiliaryConfig() {
        return auxiliaryConfig;
    }

    public AuxiliaryProperties getAuxiliaryProperties() {
        return auxiliaryProperties;
    }

    @Override
    public void resultChanged(LookupEvent ev) {
        synchronized (lock) {
            lastModels.clear();
            lastModels.addAll(modelLookupResult.allInstances());
            icModels.set(lastModels, null);
        }
    }

    @Override
    public Lookup getLookup() {
        return proxyLookup;
    }

    protected abstract Class[] getGradleModels();

    @Override
    public void run() {
        GradleHandler.getDefault().refreshModelLookup(this, getGradleModels());
    }

    public AndroidSdk getProjectSdk() {
        return projectSdk;
    }

    public void changeDefaultSdk(AndroidSdk sdk) {
        if (projectSdk != null) {
            ic.remove(projectSdk);
        }
        projectSdk = sdk;
        ic.add(sdk);

    }

    private void refreshSdk() {
        synchronized (lock) {
            if (projectSdk != null) {
                AndroidSdk currentSdk = projectSdk;
                initSdk(projectDirectory);
                if (!currentSdk.equals(projectSdk)) {
                    ic.remove(currentSdk);
                    ic.add(projectSdk);
                }
            } else {
                initSdk(projectDirectory);
                if (projectSdk != null) {
                    ic.add(projectSdk);
                }
            }
        }

    }

    private void maybeChangeSdk() {
        localPropertiesChangeTimer.restart();
    }

    private void initSdk(FileObject projectDirectory1) {
        localPropertiesFo = findAndroidLocalProperties(projectDirectory1, this);
        if (localPropertiesFo == null) {
            Project rootProject = AndroidProjects.findRootProject(projectDirectory1, this);
            try {
                localPropertiesFo = rootProject.getProjectDirectory().createData(LOCAL_PROPERTIES);
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
        if (localPropertiesFo != null) {
            Properties properties = new Properties();
            try {
                properties.load(localPropertiesFo.getInputStream());
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
            final String sdkDir = properties.getProperty(SDK_DIR, null);
            if (sdkDir == null || !AndroidSdkTools.isSdkFolder(new File(sdkDir))) {
                //no local.properties
                //no default SDK
                projectSdk = handleDefaultSdk(properties);
            } else {
                //we have valid SDK folder
                projectSdk = AndroidSdkProvider.findSdk(new File(sdkDir));
                if (projectSdk == null) {
                    NotifyDescriptor nd = new NotifyDescriptor.Confirmation("<html>Project " + projectDirectory1.getName() + " contains a SDK that is not defined in the IDE.<br>Do you want to add this SDK?<br>When you choose No, the default SDK will be used.</html>", "Android SDK import", NotifyDescriptor.YES_NO_OPTION, NotifyDescriptor.QUESTION_MESSAGE);
                    Object notify = DialogDisplayer.getDefault().notify(nd);
                    if (NotifyDescriptor.YES_OPTION.equals(notify)) {
                        NotifyDescriptor.InputLine nd1 = new NotifyDescriptor.InputLine("Please enter the name of SDK", "Android SDK import", NotifyDescriptor.OK_CANCEL_OPTION, NotifyDescriptor.QUESTION_MESSAGE);
                        Object notify1;
                        String name = null;
                        do {
                            notify1 = DialogDisplayer.getDefault().notify(nd1);
                            name = nd1.getInputText();
                        } while (!NotifyDescriptor.OK_OPTION.equals(notify1) && name != null && !name.isEmpty());
                        final String sdkName = name;
                        //this thread holds ProjectManager.mutex().read must be called outside
                        Callable<AndroidSdk> run = new Callable<AndroidSdk>() {
                            @Override
                            public AndroidSdk call() throws Exception {
                                AndroidSdkImpl defaultSdk = new AndroidSdkImpl(sdkName, sdkDir);
                                PlatformConvertor.create(defaultSdk);
                                return defaultSdk;
                            }
                        };
                        Future<AndroidSdk> submit = Executors.newSingleThreadExecutor().submit(run);
                        try {
                            projectSdk = submit.get();
                        } catch (InterruptedException | ExecutionException ex) {
                            Exceptions.printStackTrace(ex);
                        }
                        if (projectSdk != null) {
                            storeLocalProperties(properties, projectSdk);
                        }
                    } else {
                        //use default SDK
                        projectSdk = handleDefaultSdk(properties);
                    }
                }
                //SDK from local.properties is OK continue
            }
        }
    }

    private AndroidSdk handleDefaultSdk(Properties properties) {
        AndroidSdk defaultSdkTmp = AndroidSdkProvider.getDefaultSdk();
        if (defaultSdkTmp == null || defaultSdkTmp.isBroken()) {//no default SDK
            NotifyDescriptor nd2 = null;
            if (defaultSdkTmp == null) {
                nd2 = new NotifyDescriptor.Confirmation("<html>To open "
                        + getProjectDirectory().getName()
                        + " Project, you need to define an Android SDK location!<br>Do you want to set or download the android SDK?"
                        + "</html>", "Android SDK location", NotifyDescriptor.YES_NO_OPTION, NotifyDescriptor.WARNING_MESSAGE);
                Object notify2 = DialogDisplayer.getDefault().notify(nd2);
                if (NotifyDescriptor.YES_OPTION.equals(notify2)) {
                    SdksCustomizer.showCustomizer();
                    defaultSdkTmp = AndroidSdkProvider.getDefaultSdk();
                    if (defaultSdkTmp != null) {
                        storeLocalProperties(properties, defaultSdkTmp);
                        return defaultSdkTmp;
                    }
                }
            } else {
                nd2 = new NotifyDescriptor.Confirmation("<html><b>Broken Android SDK!</b><br>"
                        + "To open " + getProjectDirectory().getName()
                        + " Project, you need to define an Android SDK location!<br>Do you want to set or download the android SDK?"
                        + "</html>", "Android SDK location", NotifyDescriptor.YES_NO_OPTION, NotifyDescriptor.WARNING_MESSAGE);
                Object notify2 = DialogDisplayer.getDefault().notify(nd2);
                if (NotifyDescriptor.YES_OPTION.equals(notify2)) {
                    SdksCustomizer.showCustomizer();
                    if (defaultSdkTmp.isValid()) {
                        storeLocalProperties(properties, defaultSdkTmp);
                        return defaultSdkTmp;
                    }
                }
            }

        } else {
            storeLocalProperties(properties, defaultSdkTmp);
            return defaultSdkTmp;
        }
        return null;
    }

    public void storeLocalProperties(Properties properties, AndroidSdk defaultPlatform) {
        FileOutputStream fo = null;
        try {
            //have default SDK write to properties
            properties.setProperty(SDK_DIR, defaultPlatform.getInstallFolder().getPath());
            fo = new FileOutputStream(FileUtil.toFile(localPropertiesFo));
            try {
                properties.store(fo, COMMENT.replace("#DATE", new Date().toString()));
            } finally {
                try {
                    fo.close();
                } catch (IOException iOException) {
                }
            }
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        } finally {
            try {
                fo.close();
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
    }

    private FileObject findAndroidLocalProperties(FileObject root, Project current) {
        FileObject fo = root.getFileObject("local.properties");
        if (fo != null) {
            return fo;
        } else {
            Project owner = FileOwnerQuery.getOwner(root.getParent());
            if ((owner instanceof NbAndroidRootProjectImpl) && !owner.equals(current)) {
                return findAndroidLocalProperties(root.getParent(), current);
            } else {
                return null;
            }
        }
    }

    @Override
    public void fileFolderCreated(FileEvent fe) {
    }

    @Override
    public void fileDataCreated(FileEvent fe) {
        maybeChangeSdk();
    }

    @Override
    public void fileChanged(FileEvent fe) {
        maybeChangeSdk();
    }

    @Override
    public void fileDeleted(FileEvent fe) {
        maybeChangeSdk();
    }

    @Override
    public void fileRenamed(FileRenameEvent fe) {
        maybeChangeSdk();
    }

    @Override
    public void fileAttributeChanged(FileAttributeEvent fe) {
    }

}
