/**********************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved.   This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors: 
 * IBM - Initial API and implementation
 **********************************************************************/
package org.eclipse.pde.internal.build.builder;

import java.io.*;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.model.PluginModel;
import org.eclipse.pde.internal.build.*;
import org.eclipse.pde.internal.build.ant.FileSet;
import org.eclipse.update.core.*;
import org.eclipse.update.core.model.IncludedFeatureReferenceModel;

/**
 * Generates build.xml script for features.
 */
public class FeatureBuildScriptGenerator extends AbstractBuildScriptGenerator {
	// GENERATION FLAGS
	/** Indicates whether scripts for this feature included features should be generated. */
	protected boolean analyseIncludedFeatures = false;
	/** Indicates whether scripts for this feature children' should be generated. */
	protected boolean analysePlugins = true;
	/** Indicates whether a source feature should be generated for this feature */
	protected boolean sourceFeatureGeneration = false;
	/** Indicates whether the feature is binary */
	protected boolean binaryFeature = true;
	/** Indicates if the build scripts files should be produced or not */
	private boolean scriptGeneration = true;

	//FEATURE RELATED INFORMATION
	/** The identifier of the feature that the build script is being generated for. */
	protected String featureIdentifier;
	/** Target feature. */
	protected IFeature feature;
	/** The featurename with its version number */
	protected String featureFullName;
	protected String featureFolderName;
	protected String featureRootLocation;

	protected String featureTempFolder;

	protected Feature sourceFeature;
	protected PluginEntry sourcePlugin;
	protected String sourceFeatureFullName;
	protected String sourceFeatureFullNameVersionned;
	protected SourceFeatureInformation sourceToGather;
	protected boolean sourcePluginOnly = false;

	private String[] extraPlugins = new String[0];

	public FeatureBuildScriptGenerator() {
	}

	/**
	 * Constructor FeatureBuildScriptGenerator.
	 * @param string
	 */
	public FeatureBuildScriptGenerator(String featureId, AssemblyInformation informationGathering) throws CoreException {
		if (featureId == null) {
			String message = Policy.bind("error.missingFeatureId"); //$NON-NLS-1$
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_FEATURE_MISSING, message, null));
		}

		this.featureIdentifier = featureId;
		assemblyData = informationGathering;
	}

	/**
	 * Returns a list of PluginModel objects representing the elements. The boolean
	 * argument indicates whether the list should consist of plug-ins or fragments.
	 * 
	 * @param fragments
	 * @return List of PluginModel
	 * @throws CoreException
	 */
	protected List computeElements(boolean fragments) throws CoreException {
		List result = new ArrayList(5);

		IPluginEntry[] pluginList = feature.getPluginEntries();

		for (int i = 0; i < pluginList.length; i++) {
			IPluginEntry entry = pluginList[i];

			if (fragments == entry.isFragment()) { // filter the plugins or fragments
				VersionedIdentifier identifier = entry.getVersionedIdentifier();
				PluginModel model;

				// If we ask for 0.0.0, the call to the registry must have null as a parameter
				String versionRequested = identifier.getVersion().toString();
				if (versionRequested.equals(GENERIC_VERSION_NUMBER))
					versionRequested = null;

				if (fragments)
					model = getSite(false).getPluginRegistry().getFragment(identifier.getIdentifier(), versionRequested);
				else
					model = getSite(false).getPluginRegistry().getPlugin(identifier.getIdentifier(), versionRequested);

				if (model == null && getBuildProperties().containsKey(GENERATION_SOURCE_PLUGIN_PREFIX + identifier.getIdentifier())) {
					generateEmbeddedSource(identifier.getIdentifier());
					model = getSite(true).getPluginRegistry().getPlugin(identifier.getIdentifier(), versionRequested);
				}

				if (model == null) {
					String message = Policy.bind("exception.missingPlugin", entry.getVersionedIdentifier().toString()); //$NON-NLS-1$
					throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_PLUGIN_MISSING, message, null));
				} else {
					result.add(model);
					getCompiledElements().add(model.getId());
				}

				collectElementToAssemble(pluginList[i]);
				collectSourcePlugins(pluginList[i], model);
			}
		}
		return result;
	}

	private void generateEmbeddedSource(String pluginId) throws CoreException {
		FeatureBuildScriptGenerator featureGenerator = new FeatureBuildScriptGenerator(Utils.getArrayFromString(getBuildProperties().getProperty(GENERATION_SOURCE_PLUGIN_PREFIX + pluginId))[0], assemblyData);
		featureGenerator.setGenerateIncludedFeatures(false);
		featureGenerator.setAnalyseChildren(analysePlugins);
		featureGenerator.setSourceFeatureGeneration(true);
		featureGenerator.setExtraPlugins(Utils.getArrayFromString(getBuildProperties().getProperty(GENERATION_SOURCE_PLUGIN_PREFIX + pluginId)));
		featureGenerator.setBinaryFeatureGeneration(false);
		featureGenerator.setScriptGeneration(false);
		featureGenerator.setPluginPath(pluginPath);
		featureGenerator.setBuildSiteFactory(siteFactory);
		featureGenerator.setDevEntries(devEntries);
		featureGenerator.setCompiledElements(getCompiledElements());
		featureGenerator.setSourceToGather(sourceToGather);
		featureGenerator.setSourcePluginOnly(true);
		featureGenerator.generate();
	}

	public void setSourcePluginOnly(boolean b) {
		sourcePluginOnly = b;
	}

	private void collectSourcePlugins(IPluginEntry pluginEntry, PluginModel model) {
		if (!sourceFeatureGeneration)
			return;

		// The generic entry may not be part of the configuration we are building however, 
		// the code for a non platform specific plugin still needs to go into a generic source plugin
		if (pluginEntry.getOS() == null && pluginEntry.getWS() == null && pluginEntry.getOSArch() == null) {
			sourceToGather.addElementEntry(Config.genericConfig(), model);
			return;
		}

		// Here we fan the plugins into the source fragment where they should go 
		List correctConfigs = selectConfigs(pluginEntry);
		for (Iterator iter = correctConfigs.iterator(); iter.hasNext();) {
			sourceToGather.addElementEntry((Config) iter.next(), model);
		}
	}

	/**
	 * Set the boolean for whether or not children scripts should be generated.
	 * 
	 * @param generate <code>true</code> if the children scripts should be generated,
	 *     <code>false</code> otherwise
	 */
	public void setAnalyseChildren(boolean generate) {
		analysePlugins = generate;
	}

	/**
	 * @see AbstractScriptGenerator#generate()
	 */
	public void generate() throws CoreException {
		String message;
		if (workingDirectory == null) {
			message = Policy.bind("error.missingInstallLocation"); //$NON-NLS-1$
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_BUILDDIRECTORY_LOCATION_MISSING, message, null)); //$NON-NLS-1$
		}

		initializeVariables();

		// if the feature defines its own custom script, we do not generate a new one
		// but we do try to update the version number
		String custom = (String) getBuildProperties().get(PROPERTY_CUSTOM);
		if (custom != null && custom.equalsIgnoreCase("true")) { //$NON-NLS-1$
			File buildFile = new File(featureRootLocation, DEFAULT_BUILD_SCRIPT_FILENAME);
			try {
				updateVersion(buildFile, PROPERTY_FEATURE_VERSION_SUFFIX, feature.getVersionedIdentifier().getVersion().toString());
			} catch (IOException e) {
				message = Policy.bind("exception.writeScript"); //$NON-NLS-1$
				throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_SCRIPT, message, e));
			}
			return;
		}

		if (sourceFeatureGeneration)
			generateSourceFeature();

		if (analysePlugins)
			generateChildrenScripts();

		if (sourceFeatureGeneration) {
			addSourceFragmentsToFeature();
			writeSourceFeature();
			getSite(true);
		}

		if (!sourcePluginOnly)
			collectElementToAssemble(getSite(false).findFeature(feature.getVersionedIdentifier().getIdentifier()));

		// Do the recursive generation of build files for the features required by the current feature
		if (analyseIncludedFeatures)
			generateIncludedFeatureBuildFile();

		if (sourceFeatureGeneration)
			generateSourceFeatureScripts();

		if (scriptGeneration) {
			openScript(featureRootLocation, DEFAULT_BUILD_SCRIPT_FILENAME);
			try {
				generateBuildScript();
			} finally {
				closeScript();
			}
		}
	}

	protected void generateIncludedFeatureBuildFile() throws CoreException {
		IIncludedFeatureReference[] referencedFeatures = feature.getIncludedFeatureReferences();
		for (int i = 0; i < referencedFeatures.length; i++) {
			String featureId = ((IncludedFeatureReferenceModel) referencedFeatures[i]).getFeatureIdentifier();

			//If the feature which is included is a source feature, then instead of calling the generation of the featureID we are calling the generation
			// of the corresponding binary feature but without generating the scripts (set binaryFeatureGeneration to false)
			boolean doSourceFeatureGeneration = getBuildProperties().containsKey(GENERATION_SOURCE_FEATURE_PREFIX + featureId);
			FeatureBuildScriptGenerator generator = new FeatureBuildScriptGenerator(doSourceFeatureGeneration == true ? Utils.getArrayFromString(getBuildProperties().getProperty(GENERATION_SOURCE_FEATURE_PREFIX + featureId))[0] : featureId, assemblyData);
			generator.setGenerateIncludedFeatures(doSourceFeatureGeneration ? false : true); //If we are generating a source feature we don't want to go recursively
			generator.setAnalyseChildren(analysePlugins);
			generator.setSourceFeatureGeneration(doSourceFeatureGeneration);
			generator.setBinaryFeatureGeneration(!doSourceFeatureGeneration);
			generator.setScriptGeneration(doSourceFeatureGeneration ? false : true); //We don't want to regenerate the scripts for the binary feature we are reading to build the source feature
			if (doSourceFeatureGeneration)
				generator.setExtraPlugins(Utils.getArrayFromString(getBuildProperties().getProperty(GENERATION_SOURCE_FEATURE_PREFIX + featureId)));

			generator.setPluginPath(pluginPath);
			generator.setBuildSiteFactory(siteFactory);
			generator.setDevEntries(devEntries);
			generator.setCompiledElements(getCompiledElements());
			generator.setSourceToGather(new SourceFeatureInformation());
			generator.generate();
		}
	}

	protected void setExtraPlugins(String[] plugins) {
		extraPlugins = plugins;
	}

	/**
	 * Main call for generating the script.
	 * 
	 * @param script the script to add the Ant target to
	 * @throws CoreException
	 */
	private void generateBuildScript() throws CoreException {
		getSite(true);
		generatePrologue();
		generateAllPluginsTarget();
		generateAllFeaturesTarget();
		generateUpdateFeatureFile();
		generateAllChildrenTarget();
		generateChildrenTarget();
		generateBuildJarsTarget();
		generateBuildZipsTarget();
		generateBuildUpdateJarTarget();
		generateGatherBinPartsTarget();
		generateZipDistributionWholeTarget();
		generateZipSourcesTarget();
		generateZipLogsTarget();
		generateCleanTarget();
		generateRefreshTarget();
		generateGatherSourcesTarget();
		generateGatherLogsTarget();
		generateEpilogue();
	}

	/**
	 * Method generateGatherSource. Used to enable the recursive call of gathering
	 * the sources for the features
	 * @param script
	 */
	private void generateGatherSourcesTarget() throws CoreException {
		script.printTargetDeclaration(TARGET_GATHER_SOURCES, null, null, null, null);
		Map params = new HashMap(2);
		params.put(PROPERTY_DESTINATION_TEMP_FOLDER, getPropertyFormat(PROPERTY_FEATURE_BASE) + "/" + DEFAULT_PLUGIN_LOCATION + "/" + sourceFeatureFullNameVersionned + "/" + "src"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		params.put(PROPERTY_TARGET, TARGET_GATHER_SOURCES);
		script.printAntCallTask(TARGET_CHILDREN, null, params);
		script.printTargetEnd();
	}

	/**
	 * Method generateGatherSource. Used to enable the recursive call of gathering
	 * the sources for the features
	 * @param script
	 */
	private void generateGatherLogsTarget() {
		script.printTargetDeclaration(TARGET_GATHER_LOGS, null, null, null, null);
		script.printAntCallTask(TARGET_ZIP_LOGS, null, null);
		script.printTargetEnd();
	}

	private void generateUpdateFeatureFile() {
		script.printTargetDeclaration(TARGET_UPDATE_FEATURE_FILE, TARGET_INIT, null, null, null);
		script.printTargetEnd();
	}

	/**
	 * Add the <code>build.zips</code> target to the given Ant script.
	 * 
	 * @param script the script to add the target to
	 * @throws CoreException
	 */
	private void generateBuildZipsTarget() throws CoreException {
		StringBuffer zips = new StringBuffer();
		Properties props = getBuildProperties();
		for (Iterator iterator = props.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry entry = (Map.Entry) iterator.next();
			String key = (String) entry.getKey();
			if (key.startsWith(PROPERTY_SOURCE_PREFIX) && key.endsWith(PROPERTY_ZIP_SUFFIX)) {
				String zipName = key.substring(PROPERTY_SOURCE_PREFIX.length());
				zips.append(','); //$NON-NLS-1$
				zips.append(zipName);
				generateZipIndividualTarget(zipName, (String) entry.getValue());
			}
		}
		script.println();
		script.printTargetDeclaration(TARGET_BUILD_ZIPS, TARGET_INIT + zips.toString(), null, null, null);
		Map params = new HashMap(2);
		params.put(PROPERTY_TARGET, TARGET_BUILD_ZIPS);
		script.printAntCallTask(TARGET_ALL_CHILDREN, null, params);
		script.printTargetEnd();
	}

	/**
	 * Add a <code>zip</code> target to the given Ant script.
	 * 
	 * @param script the script to add the targets to
	 * @param zipName the name of the zip file to create
	 * @param source the directory name to read the files from
	 * @throws CoreException
	 */
	private void generateZipIndividualTarget(String zipName, String source) throws CoreException {
		script.println();
		script.printTargetDeclaration(zipName, TARGET_INIT, null, null, null);
		script.printZipTask(getPropertyFormat(PROPERTY_BASEDIR) + "/" + zipName, getPropertyFormat(PROPERTY_BASEDIR) + "/" + source, false, null); //$NON-NLS-1$ //$NON-NLS-2$
		script.printTargetEnd();
	}

	/**
	 * Add the <code>clean</code> target to the given Ant script.
	 * 
	 * @param script the script to add the target to
	 * @throws CoreException
	 */
	private void generateCleanTarget() throws CoreException {
		script.println();
		IPath basedir = new Path(getPropertyFormat(PROPERTY_BASEDIR));
		script.printTargetDeclaration(TARGET_CLEAN, TARGET_INIT, null, null, Policy.bind("build.feature.clean", featureIdentifier)); //$NON-NLS-1$
		script.printDeleteTask(null, basedir.append(featureFullName + ".jar").toString(), null); //$NON-NLS-1$
		script.printDeleteTask(null, basedir.append(featureFullName + ".bin.dist.zip").toString(), null); //$NON-NLS-1$
		script.printDeleteTask(null, basedir.append(featureFullName + ".log.zip").toString(), null); //$NON-NLS-1$
		script.printDeleteTask(null, basedir.append(featureFullName + ".src.zip").toString(), null); //$NON-NLS-1$
		script.printDeleteTask(featureTempFolder, null, null);
		Map params = new HashMap(2);
		params.put(PROPERTY_TARGET, TARGET_CLEAN);
		script.printAntCallTask(TARGET_ALL_CHILDREN, null, params);
		script.printTargetEnd();
	}

	/**
	 * Add the <code>zip.logs</code> target to the given Ant script.
	 * 
	 * @param script the script to add the target to
	 */
	private void generateZipLogsTarget() {
		script.println();
		script.printTargetDeclaration(TARGET_ZIP_LOGS, TARGET_INIT, null, null, null);
		script.printDeleteTask(featureTempFolder, null, null);
		script.printMkdirTask(featureTempFolder);
		Map params = new HashMap(1);
		params.put(PROPERTY_TARGET, TARGET_GATHER_LOGS);
		params.put(PROPERTY_DESTINATION_TEMP_FOLDER, new Path(featureTempFolder).append(DEFAULT_PLUGIN_LOCATION).toString()); //$NON-NLS-1$
		script.printAntCallTask(TARGET_ALL_CHILDREN, "false", params); //$NON-NLS-1$
		IPath destination = new Path(getPropertyFormat(PROPERTY_BASEDIR)).append(featureFullName + ".log.zip"); //$NON-NLS-1$
		script.printZipTask(destination.toString(), featureTempFolder, true, null);
		script.printDeleteTask(featureTempFolder, null, null);
		script.printTargetEnd();
	}

	/**
	 * Add the <code>zip.sources</code> target to the given Ant script.
	 * 
	 * @param script the script to add the target to
	 */
	protected void generateZipSourcesTarget() {
		script.println();
		script.printTargetDeclaration(TARGET_ZIP_SOURCES, TARGET_INIT, null, null, null);
		script.printDeleteTask(featureTempFolder, null, null);
		script.printMkdirTask(featureTempFolder);
		Map params = new HashMap(1);
		params.put(PROPERTY_TARGET, TARGET_GATHER_SOURCES);
		params.put(PROPERTY_DESTINATION_TEMP_FOLDER, featureTempFolder + "/" + DEFAULT_PLUGIN_LOCATION + "/" + sourceFeatureFullNameVersionned + "/" + "src"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		script.printAntCallTask(TARGET_ALL_CHILDREN, null, params);
		script.printZipTask(getPropertyFormat(PROPERTY_BASEDIR) + "/" + featureFullName + ".src.zip", featureTempFolder, true, null); //$NON-NLS-1$ //$NON-NLS-2$
		script.printDeleteTask(featureTempFolder, null, null);
		script.printTargetEnd();
	}

	/**
	 * Add the <code>gather.bin.parts</code> target to the given Ant script
	 * 
	 * @param script the script to add the target to
	 * @throws CoreException
	 */
	private void generateGatherBinPartsTarget() throws CoreException {
		script.println();
		script.printTargetDeclaration(TARGET_GATHER_BIN_PARTS, TARGET_INIT, PROPERTY_FEATURE_BASE, null, null);
		Map params = new HashMap(1);
		params.put(PROPERTY_TARGET, TARGET_GATHER_BIN_PARTS);
		params.put(PROPERTY_DESTINATION_TEMP_FOLDER, new Path(getPropertyFormat(PROPERTY_FEATURE_BASE)).append(DEFAULT_PLUGIN_LOCATION).toString()); //$NON-NLS-1$
		script.printAntCallTask(TARGET_CHILDREN, null, params);
		String include = (String) getBuildProperties().get(PROPERTY_BIN_INCLUDES);
		String exclude = (String) getBuildProperties().get(PROPERTY_BIN_EXCLUDES);
		String root = getPropertyFormat(PROPERTY_FEATURE_BASE) + "/" + featureFolderName; //$NON-NLS-1$
		script.printMkdirTask(root);

		if (include != null || exclude != null) {
			FileSet fileSet = new FileSet(getPropertyFormat(PROPERTY_BASEDIR), null, include, null, exclude, null, null);
			script.printCopyTask(null, root, new FileSet[] { fileSet });
		}

		// Generate the parameters for the Id Replacer.
		String featureVersionInfo = ""; //$NON-NLS-1$
		IIncludedFeatureReference[] includedFeatures = feature.getRawIncludedFeatureReferences();
		for (int i = 0; i < includedFeatures.length; i++) {
			IFeature includedFeature = getSite(false).findFeature(includedFeatures[i].getVersionedIdentifier().getIdentifier());
			VersionedIdentifier includedFeatureVersionId = includedFeature.getVersionedIdentifier();
			featureVersionInfo += (includedFeatureVersionId.getIdentifier() + "," + includedFeatureVersionId.getVersion().toString() + ","); //$NON-NLS-1$ //$NON-NLS-2$
		}

		String pluginVersionInfo = ""; //$NON-NLS-1$
		IPluginEntry[] pluginsIncluded = feature.getRawPluginEntries();
		for (int i = 0; i < pluginsIncluded.length; i++) {
			VersionedIdentifier identifier = pluginsIncluded[i].getVersionedIdentifier();
			PluginModel model;

			// If we ask for 0.0.0, the call to the registry must have null as a parameter
			String versionRequested = identifier.getVersion().toString();
			if (versionRequested.equals(GENERIC_VERSION_NUMBER))
				versionRequested = null;

			String entryIdentifier = identifier.getIdentifier();
			if (pluginsIncluded[i].isFragment())
				model = getSite(false).getPluginRegistry().getFragment(entryIdentifier, versionRequested);
			else
				model = getSite(false).getPluginRegistry().getPlugin(entryIdentifier, versionRequested);
			//TODO Here we should not always look in the registry, because the plugin may have not been dl because we know its number from the feature.xml 
			pluginVersionInfo += (entryIdentifier + "," + model.getVersion() + ","); //$NON-NLS-1$ //$NON-NLS-2$
		}
		script.print("<eclipse.idReplacer featureFilePath=\"" + root + "/" + DEFAULT_FEATURE_FILENAME_DESCRIPTOR + "\" featureIds=\"" + featureVersionInfo + "\" pluginIds=\"" + pluginVersionInfo + "\"/>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

		generateRootFilesAndPermissionsCalls();
		script.printTargetEnd();
		generateRootFilesAndPermissions();
	}

	/**
	 * 
	 */
	private void generateRootFilesAndPermissionsCalls() {
		script.printAntCallTask("ROOTFILES" + getPropertyFormat(PROPERTY_OS) + "_" + getPropertyFormat(PROPERTY_WS) + "_" + getPropertyFormat(PROPERTY_ARCH), null, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/**
	 * 
	 */
	private void generateRootFilesAndPermissions() throws CoreException {
		for (Iterator iter = getConfigInfos().iterator(); iter.hasNext();) {
			Config aConfig = (Config) iter.next();
			script.printTargetDeclaration("ROOTFILES" + aConfig.toString("_"), null, null, null, null); //$NON-NLS-1$ //$NON-NLS-2$
			generateCopyRootFiles(aConfig);
			generatePermissions(aConfig);
			script.printTargetEnd();
		}
	}

	private void generateCopyRootFiles(Config aConfig) throws CoreException {
		String configName;
		String baseList = getBuildProperties().getProperty("root", ""); //$NON-NLS-1$ //$NON-NLS-2$
		String fileList = getBuildProperties().getProperty("root." + aConfig.toString("."), ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		fileList = (fileList.length() == 0 ? "" : fileList + ",") + baseList; //$NON-NLS-1$ //$NON-NLS-2$
		if (fileList.equals("")) //$NON-NLS-1$
			return;

		configName = aConfig.toStringReplacingAny(".", ANY_STRING); //$NON-NLS-1$

		script.printMkdirTask(getPropertyFormat(PROPERTY_FEATURE_BASE) + "/" + configName + "/" + getPropertyFormat(PROPERTY_COLLECTING_PLACE)); //$NON-NLS-1$ //$NON-NLS-2$

		String[] files = Utils.getArrayFromString(fileList, ","); //$NON-NLS-1$
		FileSet[] fileSet = new FileSet[files.length];
		for (int i = 0; i < files.length; i++) {
			String fromDir = getPropertyFormat(PROPERTY_BASEDIR) + "/"; //$NON-NLS-1$
			String file = files[i];
			if (file.startsWith("file:")) { //$NON-NLS-1$
				IPath target = new Path(file.substring(5));
				fileSet[i] = new FileSet(fromDir + target.removeLastSegments(1), null, target.lastSegment(), null, null, null, null);
			} else {
				fileSet[i] = new FileSet(fromDir + file, null, "**", null, null, null, null); //$NON-NLS-1$
			}
		}
		script.printCopyTask(null, getPropertyFormat(PROPERTY_FEATURE_BASE) + "/" + configName + "/" + getPropertyFormat(PROPERTY_COLLECTING_PLACE), fileSet); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private void generatePermissions(Config aConfig) throws CoreException {
		String configInfix = aConfig.toString("."); //$NON-NLS-1$
		Properties featureProperties = getBuildProperties();
		String prefixPermissions = "root." + configInfix + "." + PERMISSIONS + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		String prefixLinks = "root." + configInfix + "." + LINK; //$NON-NLS-1$ //$NON-NLS-2$
		String commonPermissions = "root." + PERMISSIONS + "."; //$NON-NLS-1$ //$NON-NLS-2$
		String commonLinks = "root." + LINK; //$NON-NLS-1$

		for (Iterator iter = featureProperties.entrySet().iterator(); iter.hasNext();) {
			Map.Entry permission = (Map.Entry) iter.next();
			String instruction = (String) permission.getKey();
			String parameters = (String) permission.getValue();

			if (instruction.startsWith(prefixPermissions)) {
				generateChmodInstruction(getPropertyFormat(PROPERTY_FEATURE_BASE) + "/" + configInfix + "/" + getPropertyFormat(PROPERTY_COLLECTING_PLACE), instruction.substring(prefixPermissions.length()), parameters); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}

			if (instruction.startsWith(prefixLinks)) {
				generateLinkInstruction(getPropertyFormat(PROPERTY_FEATURE_BASE) + "/" + configInfix + "/" + getPropertyFormat(PROPERTY_COLLECTING_PLACE), parameters); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}

			if (instruction.startsWith(commonPermissions)) {
				generateChmodInstruction(getPropertyFormat(PROPERTY_FEATURE_BASE) + "/" + configInfix + "/" + getPropertyFormat(PROPERTY_COLLECTING_PLACE), instruction.substring(commonPermissions.length()), parameters); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}

			if (instruction.startsWith(commonLinks)) {
				generateLinkInstruction(getPropertyFormat(PROPERTY_FEATURE_BASE) + "/" + configInfix + "/" + getPropertyFormat(PROPERTY_COLLECTING_PLACE), parameters); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
		}
	}

	private void generateChmodInstruction(String dir, String rights, String files) {
		// TODO Check if we want to consider rights specified with numbers
		if (rights.equals(EXECUTABLE)) {
			rights = "755"; //$NON-NLS-1$
		}
		script.printChmod(dir, rights, files);
	}

	private void generateLinkInstruction(String dir, String files) {
		String[] links = Utils.getArrayFromString(files, ","); //$NON-NLS-1$
		List arguments = new ArrayList(2);
		for (int i = 0; i < links.length; i += 2) {
			arguments.add("-s"); //$NON-NLS-1$
			arguments.add(links[i]);
			arguments.add(links[i + 1]);
			script.printExecTask("ln", dir, arguments, "Linux"); //$NON-NLS-1$ //$NON-NLS-2$
			arguments.clear();
		}
	}

	/**
	 * Add the <code>build.update.jar</code> target to the given script.
	 * 
	 * @param script the script to add the target to
	 */
	private void generateBuildUpdateJarTarget() {
		script.println();
		script.printTargetDeclaration(TARGET_BUILD_UPDATE_JAR, TARGET_INIT, null, null, Policy.bind("build.feature.buildUpdateJar", featureIdentifier)); //$NON-NLS-1$
		Map params = new HashMap(1);
		params.put(PROPERTY_TARGET, TARGET_BUILD_UPDATE_JAR);
		script.printAntCallTask(TARGET_ALL_CHILDREN, null, params);
		script.printProperty(PROPERTY_FEATURE_BASE, featureTempFolder);
		script.printDeleteTask(featureTempFolder, null, null);
		script.printMkdirTask(featureTempFolder);
		params.clear();
		params.put(PROPERTY_FEATURE_BASE, featureTempFolder);
		// Be sure to call the gather with children turned off.  The only way to do this is 
		// to clear all inherited values.  Must remember to setup anything that is really expected.
		script.printAntCallTask(TARGET_GATHER_BIN_PARTS, "false", params); //$NON-NLS-1$
		script.printJarTask(getPropertyFormat(PROPERTY_BASEDIR) + "/" + featureFullName + ".jar", featureTempFolder + "/" + featureFolderName); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		script.printDeleteTask(featureTempFolder, null, null);
		script.printTargetEnd();
	}

	/**
	 * Add the <code>zip.distribution</code> target to the given Ant script. Zip 
	 * up the whole feature.
	 * 
	 * @param script the script to add the target to
	 */
	protected void generateZipDistributionWholeTarget() {
		script.println();
		script.printTargetDeclaration(TARGET_ZIP_DISTRIBUTION, TARGET_INIT, null, null, Policy.bind("build.feature.zips", featureIdentifier)); //$NON-NLS-1$
		script.printDeleteTask(featureTempFolder, null, null);
		script.printMkdirTask(featureTempFolder);
		Map params = new HashMap(1);
		params.put(PROPERTY_FEATURE_BASE, featureTempFolder);
		params.put(PROPERTY_INCLUDE_CHILDREN, "true"); //$NON-NLS-1$
		script.printAntCallTask(TARGET_GATHER_BIN_PARTS, null, params);
		script.printZipTask(getPropertyFormat(PROPERTY_BASEDIR) + "/" + featureFullName + ".bin.dist.zip", featureTempFolder, false, null); //$NON-NLS-1$ //$NON-NLS-2$
		script.printDeleteTask(featureTempFolder, null, null);
		script.printTargetEnd();
	}

	/**
	 * Executes a given target in all children's script files.
	 * 
	 * @param script the script to add the target to
	 */
	private void generateAllChildrenTarget() {
		StringBuffer depends = new StringBuffer();
		depends.append(TARGET_INIT);
		depends.append(',');
		depends.append(TARGET_ALL_FEATURES);
		depends.append(',');
		depends.append(TARGET_ALL_PLUGINS);
		depends.append(',');
		depends.append(TARGET_UPDATE_FEATURE_FILE);

		script.println();
		script.printTargetDeclaration(TARGET_ALL_CHILDREN, depends.toString(), null, null, null);
		script.printTargetEnd();
	}

	/**
	 * Target responsible for delegating target calls to plug-in's build.xml scripts.
	 * Plugins are sorted according to the requires chain. Fragments are inserted afterward
	 * 
	 * @param script the script to add the target to
	 * @throws CoreException
	 */
	protected void generateAllPluginsTarget() throws CoreException {
		List plugins = computeElements(false);
		List fragments = computeElements(true);

		String[] sortedPlugins = Utils.computePrerequisiteOrder((PluginModel[]) plugins.toArray(new PluginModel[plugins.size()]), (PluginModel[]) fragments.toArray(new PluginModel[fragments.size()]));
		script.println();
		script.printTargetDeclaration(TARGET_ALL_PLUGINS, TARGET_INIT, null, null, null);
		Set writtenCalls = new HashSet(plugins.size() + fragments.size());

		for (int i = 0; i < sortedPlugins.length; i++) {
			PluginModel plugin = getSite(false).getPluginRegistry().getPlugin(sortedPlugins[i]);
			// the id is a fragment
			if (plugin == null)
				plugin = getSite(false).getPluginRegistry().getFragment(sortedPlugins[i]);

			// Get the os / ws / arch to pass as a parameter to the plugin
			if (writtenCalls.contains(sortedPlugins[i]))
				continue;

			writtenCalls.add(sortedPlugins[i]);
			IPluginEntry[] entries = Utils.getPluginEntry(feature, sortedPlugins[i]);
			for (int j = 0; j < entries.length; j++) {
				List list = selectConfigs(entries[j]);
				if (list.size() == 0)
					continue;

				Map params = null;
				Config aMatchingConfig = (Config) list.get(0);
				params = new HashMap(3);

				if (!aMatchingConfig.getOs().equals(Config.ANY))
					params.put(PROPERTY_OS, aMatchingConfig.getOs());
				if (!aMatchingConfig.getWs().equals(Config.ANY))
					params.put(PROPERTY_WS, aMatchingConfig.getWs());
				if (!aMatchingConfig.getArch().equals(Config.ANY))
					params.put(PROPERTY_ARCH, aMatchingConfig.getArch());

				IPath location = Utils.makeRelative(new Path(getLocation(plugin)), new Path(featureRootLocation));
				script.printAntTask(DEFAULT_BUILD_SCRIPT_FILENAME, location.toString(), getPropertyFormat(PROPERTY_TARGET), null, null, params);
			}
		}
		script.printTargetEnd();
	}

	private void generateAllFeaturesTarget() throws CoreException {
		script.printTargetDeclaration(TARGET_ALL_FEATURES, TARGET_INIT, null, null, null);

		if (analyseIncludedFeatures) {
			IIncludedFeatureReference[] features = feature.getIncludedFeatureReferences();
			for (int i = 0; i < features.length; i++) {
				String featureId = features[i].getVersionedIdentifier().getIdentifier();

				IPath location;
				IFeature includedFeature = getSite(false).findFeature(featureId);

				String includedFeatureDirectory = includedFeature.getURL().getPath();
				int j = includedFeatureDirectory.lastIndexOf(DEFAULT_FEATURE_FILENAME_DESCRIPTOR);
				if (j != -1)
					includedFeatureDirectory = includedFeatureDirectory.substring(0, j);
				location = Utils.makeRelative(new Path(includedFeatureDirectory), new Path(featureRootLocation));
				//			}

				script.printAntTask(DEFAULT_BUILD_SCRIPT_FILENAME, location.toString(), getPropertyFormat(PROPERTY_TARGET), null, null, null);
			}
		}
		script.printTargetEnd();
	}

	/**
	 * Just ends the script.
	 * 
	 * @param script the script to end
	 */
	private void generateEpilogue() {
		script.println();
		script.printProjectEnd();
	}

	/**
	 * Defines, the XML declaration, Ant project and init target.
	 * 
	 * @param script the script to annotate
	 */
	private void generatePrologue() {
		script.printProjectDeclaration(feature.getVersionedIdentifier().getIdentifier(), TARGET_BUILD_UPDATE_JAR, "."); //$NON-NLS-1$
		script.println();
		script.printTargetDeclaration(TARGET_INIT, null, null, null, null);
		script.printProperty(PROPERTY_FEATURE_TEMP_FOLDER, PROPERTY_FEATURE_TEMP_FOLDER);
		script.printTargetEnd();
	}

	/**
	 * 
	 * @throws CoreException
	 */
	private void generateChildrenScripts() throws CoreException {
		generateModels(computeElements(false));
		generateModels(computeElements(true));
	}

	/**
	 * 
	 * @param generator
	 * @param models
	 * @throws CoreException
	 */
	private void generateModels(List models) throws CoreException {
		if (scriptGeneration == false)
			return;

		if (binaryFeature == false || models.isEmpty())
			return;

		for (Iterator iterator = models.iterator(); iterator.hasNext();) {
			PluginModel model = (PluginModel) iterator.next();
			// setModel has to be called before configurePersistentProperties
			// because it reads the model's properties
			PluginBuildScriptGenerator generator = new PluginBuildScriptGenerator();
			generator.setBuildSiteFactory(siteFactory);
			generator.setModel(model);
			generator.setFeatureGenerator(this);
			generator.generate();
		}
	}

	/**
	 * Set this object's feature id to be the given value.
	 * 
	 * @param featureID the feature id
	 * @throws CoreException if the given feature id is <code>null</code>
	 */
	public void setFeature(String featureID) throws CoreException {
		if (featureID == null) {
			String message = Policy.bind("error.missingFeatureId"); //$NON-NLS-1$
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_FEATURE_MISSING, message, null));
		}
		this.featureIdentifier = featureID;
	}

	private void initializeVariables() throws CoreException {
		feature = getSite(false).findFeature(featureIdentifier);
		if (feature == null) {
			String message = Policy.bind("exception.missingFeature", featureIdentifier); //$NON-NLS-1$
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_FEATURE_MISSING, message, null));
		}

		if (featureRootLocation == null) {
			featureRootLocation = feature.getURL().getPath();
			int i = featureRootLocation.lastIndexOf(DEFAULT_FEATURE_FILENAME_DESCRIPTOR);
			if (i != -1)
				featureRootLocation = featureRootLocation.substring(0, i);
		}

		featureFullName = feature.getVersionedIdentifier().toString();
		featureFolderName = DEFAULT_FEATURE_LOCATION + "/" + featureFullName; //$NON-NLS-1$
		sourceFeatureFullName = computeSourceFeatureName(feature, false);
		sourceFeatureFullNameVersionned = computeSourceFeatureName(feature, true);
		featureTempFolder = getPropertyFormat(PROPERTY_BASEDIR) + "/" + getPropertyFormat(PROPERTY_FEATURE_TEMP_FOLDER); //$NON-NLS-1$
	}

	private String computeSourceFeatureName(IFeature featureForName, boolean withNumber) {
		return featureForName.getVersionedIdentifier().getIdentifier() + ".source" + (withNumber ? "_" + featureForName.getVersionedIdentifier().getVersion().toString() : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/**
	 * Return a properties object constructed from the build.properties file
	 * for the given feature. If no file exists, then an empty properties object
	 * is returned.
	 * 
	 * @param feature the feature to retrieve the build.properties from
	 * @return Properties the feature's build.properties
	 * @throws CoreException
	 * @see Feature
	 */
	protected Properties getBuildProperties() throws CoreException {
		if (buildProperties == null)
			buildProperties = readProperties(featureRootLocation, PROPERTIES_FILE);
		return buildProperties;
	}

	/**
	 * Add the <code>children</code> target to the given Ant script. Delegates 
	 * some target call to all-template only if the property includeChildren is set.
	 * 
	 * @param script the script to add the target to
	 */
	private void generateChildrenTarget() {
		script.println();
		script.printTargetDeclaration(TARGET_CHILDREN, null, PROPERTY_INCLUDE_CHILDREN, null, null);
		script.printAntCallTask(TARGET_ALL_CHILDREN, null, null);
		script.printTargetEnd();
	}

	/**
	 * Add the <code>build.jars</code> target to the given Ant script.
	 * 
	 * @param script the script to add the target to
	 * @throws CoreException
	 */
	private void generateBuildJarsTarget() throws CoreException {
		script.println();
		script.printTargetDeclaration(TARGET_BUILD_JARS, TARGET_INIT, null, null, Policy.bind("build.feature.buildJars", featureIdentifier)); //$NON-NLS-1$
		Map params = new HashMap(1);
		params.put(PROPERTY_TARGET, TARGET_BUILD_JARS);
		script.printAntCallTask(TARGET_ALL_CHILDREN, null, params);
		script.printTargetEnd();
		script.println();
		script.printTargetDeclaration(TARGET_BUILD_SOURCES, TARGET_INIT, null, null, null);
		params.clear();
		params.put(PROPERTY_TARGET, TARGET_BUILD_SOURCES);
		script.printAntCallTask(TARGET_ALL_CHILDREN, null, params);
		script.printTargetEnd();
	}

	/**
	 * Add the <code>refresh</code> target to the given Ant script.
	 * 
	 * @param script the script to add the target to
	 */
	private void generateRefreshTarget() {
		script.println();
		script.printTargetDeclaration(TARGET_REFRESH, TARGET_INIT, PROPERTY_ECLIPSE_RUNNING, null, null);
		script.printConvertPathTask(new Path(featureRootLocation).removeLastSegments(0).toOSString(), PROPERTY_RESOURCE_PATH, false);
		script.printRefreshLocalTask(getPropertyFormat(featureFullName), "infinite"); //$NON-NLS-1$

		Map params = new HashMap(2);
		params.put(PROPERTY_TARGET, TARGET_REFRESH);
		script.printAntCallTask(TARGET_ALL_CHILDREN, null, params);
		script.printTargetEnd();
	}

	public void setGenerateIncludedFeatures(boolean recursiveGeneration) {
		analyseIncludedFeatures = recursiveGeneration;
	}

	protected void collectElementToAssemble(IFeature featureToCollect) throws CoreException {
		if (assemblyData == null)
			return;

		List correctConfigs = selectConfigs(featureToCollect);
		// Here, we could sort if the feature is a common one or not  by comparing the size of correctConfigs 
		for (Iterator iter = correctConfigs.iterator(); iter.hasNext();) {
			Config config = (Config) iter.next();
			assemblyData.addFeature(config, feature);
		}
	}

	/**
	 * Method generateSourceFeature.
	 */
	private void generateSourceFeature() throws CoreException {
		Feature featureExample = (Feature) feature;
		sourceFeature = createSourceFeature(featureExample);
		associateExtraPlugins();
		sourcePlugin = createSourcePlugin();
		generateSourceFragment();
		getSite(true);
	}

	private void generateSourceFragment() throws CoreException {
		Map fragments = sourceToGather.getElementEntries();
		for (Iterator iter = fragments.entrySet().iterator(); iter.hasNext();) {
			Map.Entry fragmentInfo = (Map.Entry) iter.next();
			Config configInfo = (Config) fragmentInfo.getKey();
			if (configInfo.equals(Config.genericConfig()))
				continue;

			PluginEntry sourceFragment = new PluginEntry();
			String sourceFragmentId = sourceFeature.getFeatureIdentifier() + "." + configInfo.toString("."); //$NON-NLS-1$ //$NON-NLS-2$
			sourceFragment.setPluginIdentifier(sourceFragmentId);
			sourceFragment.setPluginVersion(sourceFeature.getFeatureVersion());
			sourceFragment.setOS(configInfo.getWs());
			sourceFragment.setWS(configInfo.getOs());
			sourceFragment.setArch(configInfo.getArch());
			sourceFragment.isFragment(true);
			//sourceFeature.addPluginEntryModel(sourceFragment);

			createSourceFragment(sourceFragment, sourcePlugin);
		}
		getSite(true);
	}

	//Add the relevant source fragments to the source feature
	private void addSourceFragmentsToFeature() throws CoreException {
		Map fragments = sourceToGather.getElementEntries();
		for (Iterator iter = fragments.entrySet().iterator(); iter.hasNext();) {
			Map.Entry fragmentInfo = (Map.Entry) iter.next();
			Config configInfo = (Config) fragmentInfo.getKey();
			if (configInfo.equals(Config.genericConfig()))
				continue;

			Set sourceList = (Set) fragmentInfo.getValue();
			if (sourceList.size() == 0)
				continue;

			PluginEntry sourceFragment = new PluginEntry();
			String sourceFragmentId = sourceFeature.getFeatureIdentifier() + "." + configInfo.toString("."); //$NON-NLS-1$ //$NON-NLS-2$
			sourceFragment.setPluginIdentifier(sourceFragmentId);
			sourceFragment.setPluginVersion(sourceFeature.getFeatureVersion());
			sourceFragment.setOS(configInfo.getWs());
			sourceFragment.setWS(configInfo.getOs());
			sourceFragment.setArch(configInfo.getArch());
			sourceFragment.isFragment(true);
			sourceFeature.addPluginEntryModel(sourceFragment);

			//createSourceFragment(sourceFragment, sourcePlugin);
		}
		getSite(true);
	}

	private void generateSourceFeatureScripts() throws CoreException {
		FeatureBuildScriptGenerator sourceScriptGenerator = new FeatureBuildScriptGenerator(sourceFeatureFullName, assemblyData);
		sourceScriptGenerator.setGenerateIncludedFeatures(false);
		sourceScriptGenerator.setAnalyseChildren(true);
		sourceScriptGenerator.setSourceToGather(sourceToGather);
		sourceScriptGenerator.setBinaryFeatureGeneration(true);
		sourceScriptGenerator.setSourceFeatureGeneration(false);
		sourceScriptGenerator.setScriptGeneration(true);
		sourceScriptGenerator.setPluginPath(pluginPath);
		sourceScriptGenerator.setBuildSiteFactory(siteFactory);
		sourceScriptGenerator.setDevEntries(devEntries);
		sourceScriptGenerator.setCompiledElements(getCompiledElements());
		sourceScriptGenerator.setSourcePluginOnly(sourcePluginOnly);
		sourceScriptGenerator.generate();
	}

	// Add extra plugins into the given feature.
	private void associateExtraPlugins() throws CoreException {
		for (int i = 1; i < extraPlugins.length; i++) {
			PluginModel model;
			// see if we have a plug-in or a fragment
			if (extraPlugins[i].startsWith("plugin@")) //$NON-NLS-1$
				model = getSite(false).getPluginRegistry().getPlugin(extraPlugins[i].substring(7));
			else
				model = getSite(false).getPluginRegistry().getFragment(extraPlugins[i].substring(8));

			if (model == null) {
				String message = Policy.bind("exception.missingPlugin", extraPlugins[i]); //$NON-NLS-1$
				Platform.getPlugin(PI_PDEBUILD).getLog().log(new Status(IStatus.WARNING, extraPlugins[i], EXCEPTION_PLUGIN_MISSING, message, null));
			}

			PluginEntry entry = new PluginEntry();
			entry.setPluginIdentifier(model.getId());
			entry.setPluginVersion(model.getVersion());
			sourceFeature.addPluginEntryModel(entry);
		}
	}

	/**
	 * Method createSourcePlugin.
	 */
	private PluginEntry createSourcePlugin() throws CoreException {
		//Create an object representing the plugin
		PluginEntry result = new PluginEntry();
		String sourcePluginId = sourceFeature.getFeatureIdentifier();
		result.setPluginIdentifier(sourcePluginId);
		result.setPluginVersion(sourceFeature.getFeatureVersion());
		sourceFeature.addPluginEntryModel(result);

		// create the directory for the plugin
		IPath sourcePluginDirURL = new Path(workingDirectory + "/" + DEFAULT_PLUGIN_LOCATION + "/" + getSourcePluginName(result, false)); //$NON-NLS-1$ //$NON-NLS-2$
		File sourcePluginDir = sourcePluginDirURL.toFile();
		sourcePluginDir.mkdir();

		// Create the plugin.xml
		String genericTemplateLocation = Platform.getPlugin(PI_PDEBUILD).find(new Path("templates/plugin")).getFile(); //$NON-NLS-1$
		String templatePluginXML = genericTemplateLocation + "/" + DEFAULT_PLUGIN_FILENAME_DESCRIPTOR; //$NON-NLS-1$
		StringBuffer buffer;
		try {
			buffer = readFile(new File(templatePluginXML));
		} catch (IOException e1) {
			String message = Policy.bind("exception.readingFile", templatePluginXML); //$NON-NLS-1$
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_READING_FILE, message, e1));
		}
		int beginId = scan(buffer, 0, REPLACED_PLUGIN_ID);
		buffer.replace(beginId, beginId + REPLACED_PLUGIN_ID.length(), result.getPluginIdentifier());

		//set the version number
		beginId = scan(buffer, beginId, REPLACED_PLUGIN_VERSION);
		buffer.replace(beginId, beginId + REPLACED_PLUGIN_VERSION.length(), result.getPluginVersion());
		try {
			Utils.transferStreams(new ByteArrayInputStream(buffer.toString().getBytes()), new FileOutputStream(sourcePluginDirURL.append(DEFAULT_PLUGIN_FILENAME_DESCRIPTOR).toOSString()));
		} catch (IOException e1) {
			String message = Policy.bind("exception.readingFile", templatePluginXML); //$NON-NLS-1$
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_READING_FILE, message, e1));
		}

		Collection copiedFiles = Utils.copyFiles(featureRootLocation + "/" + "sourceTemplatePlugin", sourcePluginDir.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$

		//	If a build.properties file already exist then we use it supposing it is correct.
		File buildProperty = sourcePluginDirURL.append(PROPERTIES_FILE).toFile();
		if (!buildProperty.exists()) {
			copiedFiles.add(DEFAULT_PLUGIN_FILENAME_DESCRIPTOR); //Because the plugin.xml is not copied, we need to add it to the file
			copiedFiles.add("src/**/*.zip"); //$NON-NLS-1$
			Properties sourceBuildProperties = new Properties();
			sourceBuildProperties.put(PROPERTY_BIN_INCLUDES, Utils.getStringFromCollection(copiedFiles, ",")); //$NON-NLS-1$
			sourceBuildProperties.put(SOURCE_PLUGIN_ATTRIBUTE, "true"); //$NON-NLS-1$
			try {
				OutputStream buildFile = new BufferedOutputStream(new FileOutputStream(buildProperty));
				try {
					sourceBuildProperties.store(buildFile, null); //$NON-NLS-1$
				} finally {
					buildFile.close();
				}
			} catch (FileNotFoundException e) {
				String message = Policy.bind("exception.writingFile", buildProperty.getAbsolutePath()); //$NON-NLS-1$
				throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
			} catch (IOException e) {
				String message = Policy.bind("exception.writingFile", buildProperty.getAbsolutePath()); //$NON-NLS-1$
				throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
			}
		}

		return result;
	}

	private void createSourceFragment(PluginEntry fragment, PluginEntry plugin) throws CoreException {
		// create the directory for the plugin
		Path sourceFragmentDirURL = new Path(workingDirectory + "/" + DEFAULT_PLUGIN_LOCATION + "/" + getSourcePluginName(fragment, false)); //$NON-NLS-1$ //$NON-NLS-2$
		File sourceFragmentDir = new File(sourceFragmentDirURL.toOSString());
		sourceFragmentDir.mkdir();

		try {
			// read the content of the template file
			String templateLocation = Platform.getPlugin(PI_PDEBUILD).find(new Path("templates/fragment")).getFile(); //$NON-NLS-1$
			StringBuffer buffer = readFile(new File(templateLocation + "/" + DEFAULT_FRAGMENT_FILENAME_DESCRIPTOR)); //$NON-NLS-1$

			//Set the Id of the fragment
			int beginId = scan(buffer, 0, REPLACED_FRAGMENT_ID);
			buffer.replace(beginId, beginId + REPLACED_FRAGMENT_ID.length(), fragment.getPluginIdentifier());

			//		set the version number
			beginId = scan(buffer, beginId, REPLACED_FRAGMENT_VERSION);
			buffer.replace(beginId, beginId + REPLACED_FRAGMENT_VERSION.length(), fragment.getPluginVersion());

			// Set the Id of the plugin for the fragment
			beginId = scan(buffer, beginId, REPLACED_PLUGIN_ID);
			buffer.replace(beginId, beginId + REPLACED_PLUGIN_ID.length(), plugin.getPluginIdentifier());

			//		set the version number of the plugin to which the fragment is attached to
			beginId = scan(buffer, beginId, REPLACED_PLUGIN_VERSION);
			buffer.replace(beginId, beginId + REPLACED_PLUGIN_VERSION.length(), plugin.getPluginVersion());

			Utils.transferStreams(new ByteArrayInputStream(buffer.toString().getBytes()), new FileOutputStream(sourceFragmentDirURL.append(DEFAULT_FRAGMENT_FILENAME_DESCRIPTOR).toOSString()));

			Collection copiedFiles = Utils.copyFiles(featureRootLocation + "/" + "sourceTemplateFragment", sourceFragmentDir.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$

			File buildProperty = sourceFragmentDirURL.append(PROPERTIES_FILE).toFile();
			if (!buildProperty.exists()) { //If a build.properties file already exist then we don't override it.
				copiedFiles.add(DEFAULT_PLUGIN_FILENAME_DESCRIPTOR); //Because the fragment.xml is not copied, we need to add it to the file
				copiedFiles.add("src/**"); //$NON-NLS-1$
				Properties sourceBuildProperties = new Properties();
				sourceBuildProperties.put(PROPERTY_BIN_INCLUDES, Utils.getStringFromCollection(copiedFiles, ",")); //$NON-NLS-1$
				sourceBuildProperties.put("sourcePlugin", "true"); //$NON-NLS-1$ //$NON-NLS-2$
				try {
					OutputStream buildFile = new BufferedOutputStream(new FileOutputStream(buildProperty));
					try {
						sourceBuildProperties.store(buildFile, null); //$NON-NLS-1$
					} finally {
						buildFile.close();
					}
				} catch (FileNotFoundException e) {
					String message = Policy.bind("exception.writingFile", buildProperty.getAbsolutePath()); //$NON-NLS-1$
					throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
				} catch (IOException e) {
					String message = Policy.bind("exception.writingFile", buildProperty.getAbsolutePath()); //$NON-NLS-1$
					throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
				}
			}
		} catch (IOException e) {
			String message = Policy.bind("exception.writingFile", sourceFragmentDir.getName()); //$NON-NLS-1$	
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, null));
		}
	}

	public String getSourcePluginName(PluginEntry plugin, boolean versionSuffix) {
		return plugin.getPluginIdentifier() + (versionSuffix ? "_" + plugin.getPluginVersion() : ""); //$NON-NLS-1$	//$NON-NLS-2$
	}

	public void setFeatureRootLocation(String featureLocation) {
		this.featureRootLocation = featureLocation;
	}

	/**
	 * Method setSourceToGather.
	 * @param sourceToGather
	 */
	public void setSourceToGather(SourceFeatureInformation sourceToGather) {
		this.sourceToGather = sourceToGather;
	}

	/**
	 * Sets the sourceFeatureGeneration.
	 * @param sourceFeatureGeneration The sourceFeatureGeneration to set
	 */
	public void setSourceFeatureGeneration(boolean sourceFeatureGeneration) {
		this.sourceFeatureGeneration = sourceFeatureGeneration;
	}

	/**
	 * Sets the binaryFeatureGeneration.
	 * @param binaryFeatureGeneration The binaryFeatureGeneration to set
	 */
	public void setBinaryFeatureGeneration(boolean binaryFeatureGeneration) {
		this.binaryFeature = binaryFeatureGeneration;
	}

	/**
	 * Sets the scriptGeneration.
	 * @param scriptGeneration The scriptGeneration to set
	 */
	public void setScriptGeneration(boolean scriptGeneration) {
		this.scriptGeneration = scriptGeneration;
	}

	/**
	 * Returns the sourceFeatureGeneration.
	 * @return boolean
	 */
	public boolean isSourceFeatureGeneration() {
		return sourceFeatureGeneration;
	}

	protected void collectElementToAssemble(IPluginEntry entryToCollect) throws CoreException {
		if (assemblyData == null)
			return;

		List correctConfigs = selectConfigs(entryToCollect);

		String versionRequested = entryToCollect.getVersionedIdentifier().getVersion().toString();
		if (versionRequested.equals(IPDEBuildConstants.GENERIC_VERSION_NUMBER)) {
			versionRequested = null;
		}
		PluginModel effectivePlugin = null;

		if (entryToCollect.isFragment())
			effectivePlugin = getSite(false).getPluginRegistry().getFragment(entryToCollect.getVersionedIdentifier().getIdentifier(), versionRequested);
		else
			effectivePlugin = getSite(false).getPluginRegistry().getPlugin(entryToCollect.getVersionedIdentifier().getIdentifier(), versionRequested);

		for (Iterator iter = correctConfigs.iterator(); iter.hasNext();) {
			if (entryToCollect.isFragment())
				assemblyData.addFragment((Config) iter.next(), effectivePlugin);
			else
				assemblyData.addPlugin((Config) iter.next(), effectivePlugin);
		}
	}

	// Create a feature object representing a source feature based on the featureExample
	private Feature createSourceFeature(Feature featureExample) throws CoreException {
		Feature result = new Feature();
		result.setFeatureIdentifier(computeSourceFeatureName(featureExample, false));
		result.setFeatureVersion(featureExample.getVersionedIdentifier().getVersion().toString());
		result.setLabel(featureExample.getLabelNonLocalized());
		result.setProvider(featureExample.getProviderNonLocalized());
		result.setImageURLString(featureExample.getImageURLString());

		result.setInstallHandlerModel(featureExample.getInstallHandlerModel());
		result.setDescriptionModel(featureExample.getDescriptionModel());
		result.setCopyrightModel(featureExample.getCopyrightModel());
		result.setLicenseModel(featureExample.getLicenseModel());
		result.setUpdateSiteEntryModel(featureExample.getUpdateSiteEntryModel());

		result.setOS(featureExample.getOS());
		result.setArch(featureExample.getOSArch());
		result.setWS(featureExample.getWS());

		return result;
	}

	private void writeSourceFeature() throws CoreException {
		String sourceFeatureDir = workingDirectory + "/" + DEFAULT_FEATURE_LOCATION + "/" + sourceFeatureFullName; //$NON-NLS-1$ //$NON-NLS-2$
		File sourceDir = new File(sourceFeatureDir);
		sourceDir.mkdir();

		// write the source feature to the feature.xml
		File file = new File(sourceFeatureDir + "/" + DEFAULT_FEATURE_FILENAME_DESCRIPTOR); //$NON-NLS-1$
		try {
			SourceFeatureWriter writer = new SourceFeatureWriter(new FileOutputStream(file), sourceFeature, this);
			try {
				writer.printFeature();
			} finally {
				writer.close();
			}
		} catch (IOException e) {
			String message = Policy.bind("error.creatingFeature", sourceFeature.getFeatureIdentifier()); //$NON-NLS-1$
			throw new CoreException(new Status(IStatus.OK, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
		}

		Collection copiedFiles = Utils.copyFiles(featureRootLocation + "/" + "sourceTemplateFeature", sourceFeatureDir); //$NON-NLS-1$ //$NON-NLS-2$

		File buildProperty = new File(sourceFeatureDir + "/" + PROPERTIES_FILE); //$NON-NLS-1$
		if (buildProperty.exists()) //If a build.properties file already exist then we don't override it.
			return;

		copiedFiles.add(DEFAULT_FEATURE_FILENAME_DESCRIPTOR); //Because the feature.xml is not copied, we need to add it to the file
		Properties sourceBuildProperties = new Properties();
		sourceBuildProperties.put(PROPERTY_BIN_INCLUDES, Utils.getStringFromCollection(copiedFiles, ",")); //$NON-NLS-1$
		try {
			sourceBuildProperties.store(new FileOutputStream(buildProperty), null); //$NON-NLS-1$
		} catch (FileNotFoundException e) {
			String message = Policy.bind("exception.writingFile", buildProperty.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
		} catch (IOException e) {
			String message = Policy.bind("exception.writingFile", buildProperty.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
		}
	}

}