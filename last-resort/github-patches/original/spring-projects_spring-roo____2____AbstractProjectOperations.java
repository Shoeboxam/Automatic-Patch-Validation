package org.springframework.roo.project;

import static org.springframework.roo.support.util.AnsiEscapeCode.FG_CYAN;
import static org.springframework.roo.support.util.AnsiEscapeCode.decorate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.ReferenceStrategy;
import org.springframework.roo.metadata.MetadataService;
import org.springframework.roo.model.JavaPackage;
import org.springframework.roo.process.manager.FileManager;
import org.springframework.roo.project.maven.Pom;
import org.springframework.roo.shell.Shell;
import org.springframework.roo.support.util.Assert;
import org.springframework.roo.support.util.CollectionUtils;
import org.springframework.roo.support.util.DomUtils;
import org.springframework.roo.support.util.StringUtils;
import org.springframework.roo.support.util.XmlElementBuilder;
import org.springframework.roo.support.util.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Provides common project operations. Should be subclassed by a project-specific operations subclass.
 *
 * @author Ben Alex
 * @author Adrian Colyer
 * @author Stefan Schmidt
 * @author Alan Stewart
 * @since 1.0
 */
@SuppressWarnings("deprecation")
@Component(componentAbstract = true)
@Reference(name = "feature", strategy = ReferenceStrategy.EVENT, policy = ReferencePolicy.DYNAMIC, referenceInterface = Feature.class, cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE)
public abstract class AbstractProjectOperations implements ProjectOperations {

	// Constants
	static final String ADDED = "added";
	static final String CHANGED = "changed";
	static final String REMOVED = "removed";
	static final String UPDATED = "updated";

	// Fields
	@Reference FileManager fileManager;
	@Reference MetadataService metadataService;
	@Reference PathResolver pathResolver;
	@Reference protected PomManagementService pomManagementService;
	@Reference protected Shell shell;
	private final Map<String, Feature> features = new HashMap<String, Feature>();

	/**
	 * Generates a message about the addition of the given items to the POM
	 * 
	 * @param action the past tense of the action that was performed
	 * @param items the items that were acted upon (required, can be empty)
	 * @param singular the singular of this type of item (required)
	 * @param plural the plural of this type of item (required)
	 * @return a non-<code>null</code> message
	 * @since 1.2.0
	 */
	static String getDescriptionOfChange(final String action, final Collection<String> items, final String singular, final String plural) {
		if (items.isEmpty()) {
			return "";
		}
		return highlight(action + " " + (items.size() == 1 ? singular : plural)) + " " + StringUtils.collectionToDelimitedString(items, ", ");
	}

	/**
	 * Highlights the given text
	 * 
	 * @param text the text to highlight (can be blank)
	 * @return the highlighted text
	 */
	static String highlight(final String text) {
		return decorate(text, FG_CYAN);
	}
	
	protected void bindFeature(final Feature feature) {
		if (feature != null) {
			features.put(feature.getName(), feature);
		}
	}

	protected void unbindFeature(final Feature feature) {
		if (feature != null) {
			features.remove(feature.getName());
		}
	}

	public boolean isFeatureInstalled(String featureName) {
		Feature feature = features.get(featureName);
		if (feature == null) {
			return false;
		}
		for (String moduleName : getModuleNames()) {
			if (feature.isInstalledInModule(moduleName)) {
				return true;
			}
		}
		return false;
	}

	public boolean isFeatureInstalledInFocusedModule(String... featureNames) {
		for (final String featureName : featureNames) {
			Feature feature = features.get(featureName);
			if (feature != null && feature.isInstalledInModule(getFocusedModuleName())) {
				return true;
			}
		}
		return false;
	}

	public final boolean isProjectAvailable(final String moduleName) {
		return getProjectMetadata(moduleName) != null;
	}

	public boolean isFocusedProjectAvailable() {
		return isProjectAvailable(getFocusedModuleName());
	}

	public final ProjectMetadata getProjectMetadata(final String moduleName) {
		return (ProjectMetadata) metadataService.get(ProjectMetadata.getProjectIdentifier(moduleName));
	}

	public ProjectMetadata getFocusedProjectMetadata() {
		return getProjectMetadata(getFocusedModuleName());
	}

	public Pom getFocusedModule() {
		final ProjectMetadata focusedProjectMetadata = getFocusedProjectMetadata();
		if (focusedProjectMetadata == null) {
			return null;
		}
		return focusedProjectMetadata.getPom();
	}

	public String getFocusedModuleName() {
		return pomManagementService.getFocusedModuleName();
	}

	public void setModule(final Pom module) {
		// Update window title with project name
		shell.flash(Level.FINE, "Spring Roo: " + getTopLevelPackage(module.getModuleName()), Shell.WINDOW_TITLE_SLOT);
		shell.setPromptPath(module.getModuleName());
		pomManagementService.setFocusedModule(module);
	}
	
	public Collection<Pom> getPoms() {
		return pomManagementService.getPoms();
	}

	public final Pom getPomFromModuleName(final String moduleName) {
		final ProjectMetadata projectMetadata = getProjectMetadata(moduleName);
		return projectMetadata == null ? null : projectMetadata.getPom();
	}

	public PathResolver getPathResolver() {
		return pathResolver;
	}

	public void addModuleDependency(final String moduleName) {
		if (!StringUtils.hasText(moduleName)) {
			return;
		}
		Pom focusedModule = getFocusedModule();
		Assert.notNull(focusedModule, "Focused module for '" + moduleName + "' is not available");
		
		if (StringUtils.hasText(moduleName) && StringUtils.hasText(focusedModule.getModuleName()) && !moduleName.equals(focusedModule.getModuleName())) {
			Pom externalModule = getProjectMetadata(moduleName).getPom();
			if (externalModule != null) {
				if (!externalModule.getPath().equals(focusedModule.getPath())) {
					detectCircularDependency(focusedModule, externalModule);
					Dependency dependency = new Dependency(externalModule.getGroupId(), externalModule.getArtifactId(), externalModule.getVersion());
					if (getFocusedModule().getDependenciesExcludingVersion(dependency).isEmpty()) {
						addDependency(getFocusedModuleName(), dependency);
					}
				}
			}
		}
	}

	private void detectCircularDependency(final Pom module1, final Pom module2) {
		if (module1.isDependencyRegistered(new Dependency(module2.getGroupId(), module2.getArtifactId(), module2.getVersion()))) {
			if (module2.isDependencyRegistered(new Dependency(module1.getGroupId(), module1.getArtifactId(), module1.getVersion()))) {
				throw new IllegalStateException("Circular dependency detected, '" + module1.getModuleName() + "' depends on '" + module2.getModuleName() + "' and vice versa");
			}
		}
	}

	public JavaPackage getTopLevelPackage(final String moduleName) {
		Pom pom = getPomFromModuleName(moduleName);
		if (pom != null) {
			return new JavaPackage(pom.getGroupId());
		}
		return null;
	}

	public String getProjectName(final String moduleName) {
		Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "A pom with module name '" + moduleName + "' could not be found");
		return pom.getDisplayName();
	}

	public final void addDependency(final String moduleName, final String groupId, final String artifactId, final String version, DependencyScope scope, final String classifier) {
		Assert.isTrue(isProjectAvailable(moduleName), "Dependency modification prohibited at this time");
		Assert.notNull(groupId, "Group ID required");
		Assert.notNull(artifactId, "Artifact ID required");
		Assert.hasText(version, "Version required");
		if (scope == null) {
			scope = DependencyScope.COMPILE;
		}
		Dependency dependency = new Dependency(groupId, artifactId, version, DependencyType.JAR, scope, classifier);
		addDependency(moduleName, dependency);
	}

	public final void addDependency(final String moduleName, final String groupId, final String artifactId, final String version, final DependencyScope scope) {
		addDependency(moduleName, groupId, artifactId, version, scope, "");
	}

	public final void addDependency(final String moduleName, final String groupId, final String artifactId, final String version) {
		addDependency(moduleName, groupId, artifactId, version, DependencyScope.COMPILE);
	}

	public final void removeDependency(final String moduleName, final String groupId, final String artifactId, final String version, final String classifier) {
		Assert.isTrue(isProjectAvailable(moduleName), "Dependency modification prohibited at this time");
		Assert.notNull(groupId, "Group ID required");
		Assert.notNull(artifactId, "Artifact ID required");
		Assert.hasText(version, "Version required");
		Dependency dependency = new Dependency(groupId, artifactId, version, DependencyType.JAR, DependencyScope.COMPILE, classifier);
		removeDependency(moduleName, dependency);
	}

	public final void removeDependency(final String moduleName, final String groupId, final String artifactId, final String version) {
		removeDependency(moduleName, groupId, artifactId, version, "");
	}

	public void addDependencies(final String moduleName, final Collection<? extends Dependency> dependencies) {
		Assert.isTrue(isProjectAvailable(moduleName), "Dependency modification prohibited at this time");
		Assert.notNull(dependencies, "Dependencies required");

		if (CollectionUtils.isEmpty(dependencies)) {
			return;
		}
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so dependency addition cannot be performed");
		if (pom.isAllDependenciesRegistered(dependencies)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element dependenciesElement = DomUtils.createChildIfNotExists("dependencies", document.getDocumentElement(), document);
		final List<Element> existingDependencyElements = XmlUtils.findElements("dependency", dependenciesElement);

		final List<String> newDependencies = new ArrayList<String>();
		final List<String> removedDependencies = new ArrayList<String>();
		for (final Dependency newDependency : dependencies) {
			if (newDependency != null && !pom.isDependencyRegistered(newDependency)) {
				// Look for any existing instances of this dependency
				boolean inserted = false;
				for (final Element existingDependencyElement : existingDependencyElements) {
					final Dependency existingDependency = new Dependency(existingDependencyElement);
					if (existingDependency.hasSameCoordinates(newDependency)) {
						// It's the same artifact, but might have a different version, exclusions, etc.
						if (!inserted) {
							// We haven't added the new one yet; do so now
							dependenciesElement.insertBefore(newDependency.getElement(document), existingDependencyElement);
							inserted = true;
							if (!newDependency.getVersion().equals(existingDependency.getVersion())) {
								// It's a genuine version change => mention the old and new versions in the message
								newDependencies.add(newDependency.getSimpleDescription());
								removedDependencies.add(existingDependency.getSimpleDescription());
							}
						}
						// Either way, we remove the previous one in case it was different in any way
						dependenciesElement.removeChild(existingDependencyElement);
					}
					// Keep looping in case it's present more than once
				}
				if (!inserted) {
					// We didn't encounter any existing dependencies with the same coordinates; add it now
					dependenciesElement.appendChild(newDependency.getElement(document));
					newDependencies.add(newDependency.getSimpleDescription());
				}
			}
		}
		if (!newDependencies.isEmpty()) {
			final String addMessage = getDescriptionOfChange(ADDED, newDependencies, "dependency", "dependencies");
			final String removeMessage = getDescriptionOfChange(REMOVED, removedDependencies, "dependency", "dependencies");
			final String message = StringUtils.hasText(removeMessage) ? addMessage + "; " + removeMessage : addMessage;
			fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), message, false);
		}
	}

	public void addDependency(final String moduleName, final Dependency dependency) {
		Assert.isTrue(isProjectAvailable(moduleName), "Dependency modification prohibited at this time");
		Assert.notNull(dependency, "Dependency required");
		addDependencies(moduleName, Collections.singletonList(dependency));
	}

	public void removeDependencies(final String moduleName, final Collection<? extends Dependency> dependenciesToRemove) {
		Assert.isTrue(isProjectAvailable(moduleName), "Dependency modification prohibited at this time");
		Assert.notNull(dependenciesToRemove, "Dependencies required");
		if (CollectionUtils.isEmpty(dependenciesToRemove)) {
			return;
		}
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so dependency removal cannot be performed");
		if (!pom.isAnyDependenciesRegistered(dependenciesToRemove)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();
		final Element dependenciesElement = XmlUtils.findFirstElement("/project/dependencies", root);
		if (dependenciesElement == null) {
			return;
		}

		final List<Element> existingDependencyElements = XmlUtils.findElements("dependency", dependenciesElement);
		final List<String> removedDependencies = new ArrayList<String>();
		for (final Dependency dependencyToRemove : dependenciesToRemove) {
			if (pom.isDependencyRegistered(dependencyToRemove)) {
				for (final Iterator<Element> iter = existingDependencyElements.iterator(); iter.hasNext();) {
					final Element candidate = iter.next();
					final Dependency candidateDependency = new Dependency(candidate);
					if (candidateDependency.equals(dependencyToRemove)) {
						// It's the same dependency; remove it
						dependenciesElement.removeChild(candidate);
						// Ensure we don't try to remove it again for another Dependency
						iter.remove();
						removedDependencies.add(candidateDependency.getSimpleDescription());
					}
					// Keep looping in case it's in the POM more than once
				}
			}
		}
		if (removedDependencies.isEmpty()) {
			return;
		}
		DomUtils.removeTextNodes(dependenciesElement);
		final String message = getDescriptionOfChange(REMOVED, removedDependencies, "dependency", "dependencies");

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), message, false);
	}

	public void removeDependency(final String moduleName, final Dependency dependency) {
		removeDependency(moduleName, dependency, "/project/dependencies", "/project/dependencies/dependency");
	}

	public void updateDependencyScope(final String moduleName, final Dependency dependency, final DependencyScope dependencyScope) {
		Assert.isTrue(isProjectAvailable(moduleName), "Dependency modification prohibited at this time");
		Assert.notNull(dependency, "Dependency to update required");
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so updating a dependency cannot be performed");
		if (!pom.isDependencyRegistered(dependency)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();
		final Element dependencyElement = XmlUtils.findFirstElement("/project/dependencies/dependency[groupId = '" + dependency.getGroupId() + "' and artifactId = '" + dependency.getArtifactId() + "' and version = '" + dependency.getVersion() + "']", root);
		if (dependencyElement == null) {
			return;
		}

		final Element scopeElement = XmlUtils.findFirstElement("scope", dependencyElement);
		final String descriptionOfChange;
		if (scopeElement == null) {
			if (dependencyScope != null) {
				dependencyElement.appendChild(new XmlElementBuilder("scope", document).setText(dependencyScope.name().toLowerCase()).build());
				descriptionOfChange = highlight(ADDED + " scope") + " " + dependencyScope.name().toLowerCase() + " to dependency " + dependency.getSimpleDescription();
			} else {
				descriptionOfChange = null;
			}
		} else {
			if (dependencyScope != null) {
				scopeElement.setTextContent(dependencyScope.name().toLowerCase());
				descriptionOfChange = highlight(CHANGED + " scope") + " to " + dependencyScope.name().toLowerCase() + " in dependency " + dependency.getSimpleDescription();
			} else {
				dependencyElement.removeChild(scopeElement);
				descriptionOfChange = highlight(REMOVED + " scope") + " from dependency " + dependency.getSimpleDescription();
			}
		}

		if (descriptionOfChange != null) {
			fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), descriptionOfChange, false);
		}
	}

	public void addBuildPlugins(final String moduleName, final Collection<? extends Plugin> plugins) {
		Assert.isTrue(isProjectAvailable(moduleName), "Plugin modification prohibited at this time");
		Assert.notNull(plugins, "Plugins required");
		if (CollectionUtils.isEmpty(plugins)) {
			return;
		}
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so plugin addition cannot be performed");
		if (pom.isAllPluginsRegistered(plugins)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();
		final Element pluginsElement = DomUtils.createChildIfNotExists("/project/build/plugins", root, document);

		final List<String> newPlugins = new ArrayList<String>();
		for (final Plugin plugin : plugins) {
			if (plugin != null && !pom.isPluginRegistered(plugin.getGAV())) {
				pluginsElement.appendChild(plugin.getElement(document));
				newPlugins.add(plugin.getSimpleDescription());
			}
		}
		if (newPlugins.isEmpty()) {
			return;
		}
		final String message = getDescriptionOfChange(ADDED, newPlugins, "plugin", "plugins");

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), message, false);
	}

	public void addBuildPlugin(final String moduleName, final Plugin plugin) {
		Assert.isTrue(isProjectAvailable(moduleName), "Plugin modification prohibited at this time");
		Assert.notNull(plugin, "Plugin required");
		addBuildPlugins(moduleName, Collections.singletonList(plugin));
	}

	public void removeBuildPlugins(final String moduleName, final Collection<? extends Plugin> plugins) {
		removeBuildPlugins(moduleName, plugins, false);
	}

	public void removeBuildPlugin(final String moduleName, final Plugin plugin) {
		Assert.isTrue(isProjectAvailable(moduleName), "Plugin modification prohibited at this time");
		Assert.notNull(plugin, "Plugin required");
		removeBuildPlugins(moduleName, Collections.singletonList(plugin));
	}

	public void removeBuildPluginImmediately(final String moduleName, final Plugin plugin) {
		Assert.isTrue(isProjectAvailable(moduleName), "Plugin modification prohibited at this time");
		Assert.notNull(plugin, "Plugin required");
		removeBuildPlugins(moduleName, Collections.singletonList(plugin), true);
	}

	public void updateBuildPlugin(final String moduleName, final Plugin plugin) {
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so plugins cannot be modified at this time");
		Assert.notNull(plugin, "Plugin required");
		for (Plugin existingPlugin : pom.getBuildPlugins()) {
			if (existingPlugin.equals(plugin)) {
				// Already exists, so just quit
				return;
			}
		}

		// Delete any existing plugin with a different version
		removeBuildPlugin(moduleName, plugin);

		// Add the plugin
		addBuildPlugin(moduleName, plugin);
	}

	@Deprecated
	public void buildPluginUpdate(final String moduleName, final Plugin plugin) {
		updateBuildPlugin(moduleName, plugin);
	}

	private void removeBuildPlugins(final String moduleName, final Collection<? extends Plugin> plugins, boolean writeImmediately) {
		Assert.isTrue(isProjectAvailable(moduleName), "Plugin modification prohibited at this time");
		Assert.notNull(plugins, "Plugins required");
		if (CollectionUtils.isEmpty(plugins)) {
			return;
		}
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so plugin removal cannot be performed");
		if (!pom.isAnyPluginsRegistered(plugins)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();
		final Element pluginsElement = XmlUtils.findFirstElement("/project/build/plugins", root);
		if (pluginsElement == null) {
			return;
		}

		final List<String> removedPlugins = new ArrayList<String>();
		for (final Plugin plugin : plugins) {
			// Can't filter the XPath on groupId, as it's optional in the POM for Apache-owned plugins
			for (final Element candidate : XmlUtils.findElements("plugin[artifactId = '" + plugin.getArtifactId() + "' and version = '" + plugin.getVersion() + "']", pluginsElement)) {
				final Plugin candidatePlugin = new Plugin(candidate);
				if (candidatePlugin.getGroupId().equals(plugin.getGroupId())) {
					// This element has the same groupId, artifactId, and version as the plugin to be removed; remove it
					pluginsElement.removeChild(candidate);
					removedPlugins.add(candidatePlugin.getSimpleDescription());
					// Keep looping in case this plugin is in the POM more than once (unlikely)
				}
			}
		}
		if (removedPlugins.isEmpty()) {
			return;
		}
		DomUtils.removeTextNodes(pluginsElement);
		final String message = getDescriptionOfChange(REMOVED, removedPlugins, "plugin", "plugins");

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), message, writeImmediately);
	}

	public void addRepositories(final String moduleName, final Collection<? extends Repository> repositories) {
		addRepositories(moduleName, repositories, "repositories", "repository");
	}

	public void addRepository(final String moduleName, final Repository repository) {
		addRepository(moduleName, repository, "repositories", "repository");
	}

	public void removeRepository(final String moduleName, final Repository repository) {
		removeRepository(moduleName, repository, "/project/repositories/repository");
	}

	public void addPluginRepositories(final String moduleName, final Collection<? extends Repository> repositories) {
		Assert.isTrue(isProjectAvailable(moduleName), "Plugin repository modification prohibited at this time");
		Assert.notNull(repositories, "Plugin repositories required");
		addRepositories(moduleName, repositories, "pluginRepositories", "pluginRepository");
	}

	public void addPluginRepository(final String moduleName, final Repository repository) {
		Assert.isTrue(isProjectAvailable(moduleName), "Plugin repository modification prohibited at this time");
		Assert.notNull(repository, "Repository required");
		addRepository(moduleName, repository, "pluginRepositories", "pluginRepository");
	}

	public void removePluginRepository(final String moduleName, final Repository repository) {
		Assert.isTrue(isProjectAvailable(moduleName), "Plugin repository modification prohibited at this time");
		Assert.notNull(repository, "Repository required");
		removeRepository(moduleName, repository, "/project/pluginRepositories/pluginRepository");
	}

	public void updateProjectType(final String moduleName, final ProjectType projectType) {
		Assert.notNull(projectType, "Project type required");
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so the project type cannot be changed");

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element packaging = DomUtils.createChildIfNotExists("packaging", document.getDocumentElement(), document);
		if (packaging.getTextContent().equals(projectType.getType())) {
			return;
		}

		packaging.setTextContent(projectType.getType());
		final String descriptionOfChange = highlight(UPDATED + " project type") + " to " + projectType.getType();

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), descriptionOfChange, false);
	}

	private void addRepositories(final String moduleName, final Collection<? extends Repository> repositories, final String containingPath, final String path) {
		Assert.isTrue(isProjectAvailable(moduleName), "Repository modification prohibited at this time");
		Assert.notNull(repositories, "Repositories required");

		if (CollectionUtils.isEmpty(repositories)) {
			return;
		}
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so repository addition cannot be performed");
		if ("pluginRepository".equals(path)) {
			if (pom.isAllPluginRepositoriesRegistered(repositories)) {
				return;
			}
		} else if (pom.isAllRepositoriesRegistered(repositories)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element repositoriesElement = DomUtils.createChildIfNotExists(containingPath, document.getDocumentElement(), document);

		final List<String> addedRepositories = new ArrayList<String>();
		for (final Repository repository : repositories) {
			if ("pluginRepository".equals(path)) {
				if (pom.isPluginRepositoryRegistered(repository)) {
					continue;
				}
			} else {
				if (pom.isRepositoryRegistered(repository)) {
					continue;
				}
			}
			if (repository != null) {
				repositoriesElement.appendChild(repository.getElement(document, path));
				addedRepositories.add(repository.getUrl());
			}
		}
		final String message = getDescriptionOfChange(ADDED, addedRepositories, path, containingPath);

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), message, false);
	}

	private void addRepository(final String moduleName, final Repository repository, final String containingPath, final String path) {
		Assert.isTrue(isProjectAvailable(moduleName), "Repository modification prohibited at this time");
		Assert.notNull(repository, "Repository required");
		addRepositories(moduleName, Collections.singletonList(repository), containingPath, path);
	}

	private void removeRepository(final String moduleName, final Repository repository, final String path) {
		Assert.isTrue(isProjectAvailable(moduleName), "Repository modification prohibited at this time");
		Assert.notNull(repository, "Repository required");
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so repository removal cannot be performed");
		if ("pluginRepository".equals(path)) {
			if (!pom.isPluginRepositoryRegistered(repository)) {
				return;
			}
		} else {
			if (!pom.isRepositoryRegistered(repository)) {
				return;
			}
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();

		String descriptionOfChange = "";
		for (final Element candidate : XmlUtils.findElements(path, root)) {
			if (repository.equals(new Repository(candidate))) {
				candidate.getParentNode().removeChild(candidate);
				descriptionOfChange = highlight(REMOVED + " repository") + " " + repository.getUrl();
				// We stay in the loop just in case it was in the POM more than once
			}
		}

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), descriptionOfChange, false);
	}

	public void addProperty(final String moduleName, final Property property) {
		Assert.isTrue(isProjectAvailable(moduleName), "Property modification prohibited at this time");
		Assert.notNull(property, "Property to add required");
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so property addition cannot be performed");
		if (pom.isPropertyRegistered(property)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();
		final String descriptionOfChange;
		final Element existing = XmlUtils.findFirstElement("/project/properties/" + property.getName(), root);
		if (existing == null) {
			// No existing property of this name; add it
			final Element properties = DomUtils.createChildIfNotExists("properties", document.getDocumentElement(), document);
			properties.appendChild(XmlUtils.createTextElement(document, property.getName(), property.getValue()));
			descriptionOfChange = highlight(ADDED + " property") + " '" + property.getName() + "' = '" + property.getValue() + "'";
		} else {
			// A property of this name exists; update it
			existing.setTextContent(property.getValue());
			descriptionOfChange = highlight(UPDATED + " property") + " '" + property.getName() + "' to '" + property.getValue() + "'";
		}

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), descriptionOfChange, false);
	}

	public void removeProperty(final String moduleName, final Property property) {
		Assert.isTrue(isProjectAvailable(moduleName), "Property modification prohibited at this time");
		Assert.notNull(property, "Property to remove required");
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so property removal cannot be performed");
		if (!pom.isPropertyRegistered(property)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();
		final Element propertiesElement = XmlUtils.findFirstElement("/project/properties", root);
		String descriptionOfChange = "";
		for (final Element candidate : XmlUtils.findElements("/project/properties/*", document.getDocumentElement())) {
			if (property.equals(new Property(candidate))) {
				propertiesElement.removeChild(candidate);
				descriptionOfChange = highlight(REMOVED + " property") + " " + property.getName();
				// Stay in the loop just in case it was in the POM more than once
			}
		}

		DomUtils.removeTextNodes(propertiesElement);

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), descriptionOfChange, false);
	}

	public void addFilter(final String moduleName, final Filter filter) {
		Assert.isTrue(isProjectAvailable(moduleName), "Filter modification prohibited at this time");
		Assert.notNull(filter, "Filter required");
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so filter addition cannot be performed");
		if (filter == null || pom.isFilterRegistered(filter)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();
		final String descriptionOfChange;
		final Element buildElement = XmlUtils.findFirstElement("/project/build", root);
		final Element existingFilter = XmlUtils.findFirstElement("filters/filter['" + filter.getValue() + "']", buildElement);
		if (existingFilter == null) {
			// No such filter; add it
			final Element filtersElement = DomUtils.createChildIfNotExists("filters", buildElement, document);
			filtersElement.appendChild(XmlUtils.createTextElement(document, "filter", filter.getValue()));
			descriptionOfChange = highlight(ADDED + " filter") + " '" + filter.getValue() + "'";
		} else {
			existingFilter.setTextContent(filter.getValue());
			descriptionOfChange = highlight(UPDATED + " filter") + " '" + filter.getValue() + "'";
		}

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), descriptionOfChange, false);
	}

	public void removeFilter(final String moduleName, final Filter filter) {
		Assert.isTrue(isProjectAvailable(moduleName), "Filter modification prohibited at this time");
		Assert.notNull(filter, "Filter required");
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so filter removal cannot be performed");
		if (filter == null || !pom.isFilterRegistered(filter)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();

		final Element filtersElement = XmlUtils.findFirstElement("/project/build/filters", root);
		if (filtersElement == null) {
			return;
		}

		String descriptionOfChange = "";
		for (final Element candidate : XmlUtils.findElements("filter", filtersElement)) {
			if (filter.equals(new Filter(candidate))) {
				filtersElement.removeChild(candidate);
				descriptionOfChange = highlight(REMOVED + " filter") + " '" + filter.getValue() + "'";
				// We will not break the loop (even though we could theoretically), just in case it was in the POM more than once
			}
		}

		final List<Element> filterElements = XmlUtils.findElements("filter", filtersElement);
		if (filterElements.isEmpty()) {
			filtersElement.getParentNode().removeChild(filtersElement);
		}

		DomUtils.removeTextNodes(root);

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), descriptionOfChange, false);
	}

	public void addResource(final String moduleName, final Resource resource) {
		Assert.isTrue(isProjectAvailable(moduleName), "Resource modification prohibited at this time");
		Assert.notNull(resource, "Resource to add required");
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so resource addition cannot be performed");
		if (pom.isResourceRegistered(resource)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element buildElement = XmlUtils.findFirstElement("/project/build", document.getDocumentElement());
		final Element resourcesElement = DomUtils.createChildIfNotExists("resources", buildElement, document);
		resourcesElement.appendChild(resource.getElement(document));
		final String descriptionOfChange = highlight(ADDED + " resource") + " " + resource.getSimpleDescription();

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), descriptionOfChange, false);
	}

	public void removeResource(final String moduleName, final Resource resource) {
		Assert.isTrue(isProjectAvailable(moduleName), "Resource modification prohibited at this time");
		Assert.notNull(resource, "Resource required");
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so resource removal cannot be performed");
		if (!pom.isResourceRegistered(resource)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();
		final Element resourcesElement = XmlUtils.findFirstElement("/project/build/resources", root);
		if (resourcesElement == null) {
			return;
		}
		String descriptionOfChange = "";
		for (final Element candidate : XmlUtils.findElements("resource[directory = '" + resource.getDirectory() + "']", resourcesElement)) {
			if (resource.equals(new Resource(candidate))) {
				resourcesElement.removeChild(candidate);
				descriptionOfChange = highlight(REMOVED + " resource") + " " + resource.getSimpleDescription();
				// Stay in the loop just in case it was in the POM more than once
			}
		}

		final List<Element> resourceElements = XmlUtils.findElements("resource", resourcesElement);
		if (resourceElements.isEmpty()) {
			resourcesElement.getParentNode().removeChild(resourcesElement);
		}

		DomUtils.removeTextNodes(root);

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), descriptionOfChange, false);
	}

	/**
	 * Removes an element identified by the given dependency, whenever it occurs at the given path
	 * 
	 * @param moduleName the name of the module to remove the dependency from
	 * @param dependency the dependency to remove
	 * @param containingPath the path to the dependencies element
	 * @param path the path to the individual dependency elements
	 */
	private void removeDependency(final String moduleName, final Dependency dependency, final String containingPath, final String path) {
		Assert.isTrue(isProjectAvailable(moduleName), "Dependency modification prohibited at this time");
		Assert.notNull(dependency, "Dependency to remove required");
		final Pom pom = getPomFromModuleName(moduleName);
		Assert.notNull(pom, "The pom is not available, so dependency removal cannot be performed");
		if (!pom.isDependencyRegistered(dependency)) {
			return;
		}

		final Document document = XmlUtils.readXml(fileManager.getInputStream(pom.getPath()));
		final Element root = document.getDocumentElement();

		String descriptionOfChange = "";
		final Element dependenciesElement = XmlUtils.findFirstElement(containingPath, root);
		for (final Element candidate : XmlUtils.findElements(path, root)) {
			if (dependency.equals(new Dependency(candidate))) {
				dependenciesElement.removeChild(candidate);
				descriptionOfChange = highlight(REMOVED + " dependency") + " " + dependency.getSimpleDescription();
				// Stay in the loop, just in case it was in the POM more than once
			}
		}

		DomUtils.removeTextNodes(dependenciesElement);

		fileManager.createOrUpdateTextFileIfRequired(pom.getPath(), XmlUtils.nodeToString(document), descriptionOfChange, false);
	}

	public String getFocusedProjectName() {
		return getProjectName(getFocusedModuleName());
	}

	public JavaPackage getFocusedTopLevelPackage() {
		return getTopLevelPackage(getFocusedModuleName());
	}
	
	public Pom getModuleForFileIdentifier(final String fileIdentifier) {
		return pomManagementService.getModuleForFileIdentifier(fileIdentifier);
	}
	
	public Collection<String> getModuleNames() {
		return pomManagementService.getModuleNames();
	}
	
	public boolean isModuleFocusAllowed() {
		return !getModuleNames().isEmpty();
	}
	
	public boolean isModuleCreationAllowed() {
		return isProjectAvailable("");
	}
}
