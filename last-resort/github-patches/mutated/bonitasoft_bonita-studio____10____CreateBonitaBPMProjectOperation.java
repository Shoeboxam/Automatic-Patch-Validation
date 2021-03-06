/**
 * Copyright (C) 2014 BonitaSoft S.A.
 * BonitaSoft, 32 rue Gustave Eiffel - 38000 Grenoble
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2.0 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.bonitasoft.studio.common.repository.core;

import static org.bonitasoft.studio.common.NamingUtils.toJavaIdentifier;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bonitasoft.engine.bpm.bar.BusinessArchive;
import org.bonitasoft.studio.common.ProductVersion;
import org.bonitasoft.studio.common.repository.CommonRepositoryPlugin;
import org.bonitasoft.studio.common.repository.Messages;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.pde.internal.core.util.ManifestUtils;
import org.osgi.framework.BundleReference;

/**
 * @author Romain Bioteau
 */
public class CreateBonitaBPMProjectOperation implements IWorkspaceRunnable {

    private static final String META_INF_FOLDER_NAME = "META-INF";
    private IProject project;
    private final IWorkspace workspace;
    private final String projectName;
    private final Set<String> builders = new HashSet<String>();
    private final Set<String> natures = new HashSet<String>();

    public CreateBonitaBPMProjectOperation(final IWorkspace workspace, final String projectName) {
        this.workspace = workspace;
        this.projectName = projectName;
    }

    @Override
    public void run(final IProgressMonitor monitor) throws CoreException {
        project = workspace.getRoot().getProject(projectName);
        if (project.exists()) {
            throw new CoreException(new Status(IStatus.ERROR, CommonRepositoryPlugin.PLUGIN_ID, String.format("Project %s already exists.", projectName)));
        }
        project.create(monitor);
        project.open(monitor);
        project.setDescription(
                new ProjectDescriptionBuilder().withProjectName(project.getName()).withComment(ProductVersion.CURRENT_VERSION).havingNatures(natures)
                        .havingBuilders(builders).build(), monitor);
        createJavaProject(monitor);
        createProjectManifest(monitor);
    }

    private void createJavaProject(final IProgressMonitor monitor) {
        monitor.subTask(Messages.initializingJavaProject);
        final IJavaProject javaProject = asJavaProject();
        javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_1_7);
        javaProject.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_1_7);
        javaProject.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_1_7);
        javaProject.setOption(JavaCore.CORE_JAVA_BUILD_INVALID_CLASSPATH, "ignore");
        monitor.worked(1);
    }

    protected IJavaProject asJavaProject() {
        return JavaCore.create(project);
    }

    public CreateBonitaBPMProjectOperation addBuilder(final String builderId) {
        builders.add(builderId);
        return this;
    }

    public CreateBonitaBPMProjectOperation addNature(final String natureId) {
        natures.add(natureId);
        return this;
    }

    protected void createProjectManifest(final IProgressMonitor monitor) throws CoreException {
        final IFolder metaInf = project.getFolder(META_INF_FOLDER_NAME);
        metaInf.create(false, true, monitor);
        final IFile projectManifest = project.getFolder(META_INF_FOLDER_NAME).getFile("MANIFEST.MF");
        try (PrintWriter writer = new PrintWriter(projectManifest.getLocation().toFile())) {
            ManifestUtils.writeManifest(createManifestHeaders(), writer);
            projectManifest.refreshLocal(IResource.DEPTH_ONE, monitor);
        } catch (final IOException e) {
            throw new CoreException(new Status(IStatus.ERROR, CommonRepositoryPlugin.PLUGIN_ID, "Failed to create MANIFEST.MF", e));
        }
    }

    protected Map<String, String> createManifestHeaders() {
        final Map<String, String> headers = new HashMap<String, String>();
        headers.put(ManifestUtils.MANIFEST_VERSION, "1.0");
        headers.put(org.osgi.framework.Constants.BUNDLE_MANIFESTVERSION, "2");
        headers.put(org.osgi.framework.Constants.BUNDLE_NAME, projectName);
        headers.put(org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME, toJavaIdentifier(projectName, false));
        headers.put(org.osgi.framework.Constants.BUNDLE_VERSION, "1.0.0.qualifier");
        headers.put(org.osgi.framework.Constants.BUNDLE_VENDOR, "BonitaSoft S.A.");
        headers.put(org.osgi.framework.Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT, "JavaSE-1.7");
        headers.put(org.osgi.framework.Constants.REQUIRE_BUNDLE,
                new StringBuilder()
                        .append("javax.persistence;bundle-version=\"2.0.3\"").append(",")
                        .append(System.lineSeparator())
                        .append(" ")
                        .append(engineBundleSymbolicName()).toString());
        return headers;
    }

    protected String engineBundleSymbolicName() {
        final BundleReference cl = (BundleReference) BusinessArchive.class.getClassLoader();
        final String symbolicName = cl.getBundle().getSymbolicName();
        return symbolicName;
    }

    public IProject getProject() {
        return project;
    }

}
