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
package org.eclipse.pde.internal.build;

import java.io.*;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.pde.internal.build.ant.AntScript;
import org.eclipse.update.core.IFeature;

/**
 * Generate an assemble script for a given feature and a given config. It
 * generates all the instruction to zip the listed plugins and features.
 */
public class AssembleConfigScriptGenerator extends AbstractScriptGenerator {
	protected String directory; // representing the directory where to generate the file
	protected String featureId;
	protected Config configInfo;
	protected IFeature[] features;
	protected BundleDescription[] plugins;
	protected String filename;
	protected boolean copyRootFile;
	protected Properties pluginsPostProcessingSteps;
	protected Properties featuresPostProcessingSteps;
	
	private static final String PROPERTY_TMP_DIR = "tmp_dir"; //$NON-NLS-1$	
	private static final String PROPERTY_SOURCE = "source"; //$NON-NLS-1$
	private static final String PROPERTY_ELEMENT_NAME = "elementName"; //$NON-NLS-1$
	
	private static final String updateJar = "updateJar";	//$NON-NLS-1$
	private static final String flat = "flat";	//$NON-NLS-1$
	
	private static final byte BUNDLE = 0;  
	private static final byte FEATURE =  1;
	
	private static final byte FOLDER = 0;
	private static final byte FILE = 1;
	
	public AssembleConfigScriptGenerator() {
		super();
	}

	public void initialize(String directoryName, String scriptName, String feature, Config configurationInformation, Collection elementList, Collection featureList, boolean rootFileCopy) throws CoreException {
		this.directory = directoryName;
		this.featureId = feature;
		this.configInfo = configurationInformation;
		this.copyRootFile = rootFileCopy;
		
		this.features = new IFeature[featureList.size()];
		featureList.toArray(this.features);

		this.plugins = new BundleDescription[elementList.size()];
		this.plugins = (BundleDescription[]) elementList.toArray(this.plugins);

		filename = directory + '/' + (scriptName != null ? scriptName : getFilename()); //$NON-NLS-1$
		try {
			script = new AntScript(new FileOutputStream(filename));
		} catch (FileNotFoundException e) {
			// a file doesn't exist so we will create a new one
		} catch (IOException e) {
			String message = Policy.bind("exception.writingFile", filename); //$NON-NLS-1$
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
		}
		loadPostProcessingSteps();
	}
	private void loadPostProcessingSteps() {
		pluginsPostProcessingSteps = loadPropertyFile(AbstractScriptGenerator.getWorkingDirectory() + '/' + DEFAULT_FEATURES_POSTPROCESSINGSTEPS_FILENAME_DESCRIPTOR, null);
		featuresPostProcessingSteps = loadPropertyFile(AbstractScriptGenerator.getWorkingDirectory() + '/' + DEFAULT_FEATURES_POSTPROCESSINGSTEPS_FILENAME_DESCRIPTOR, null);
	}
	public void generate() throws CoreException {
		generatePrologue();
		generateInitializationSteps();
		generateGatherBinPartsCalls();
		if (configInfo.getOs().equalsIgnoreCase("macosx")) { //$NON-NLS-1$
			generateTarTarget();
			generateGZipTarget();
		} else {
			generateZipTarget();
		}
		generateEpilogue();
	}

	private void generatePackagingTargets() {
		script.printTargetDeclaration(TARGET_JARUP, null, null, null, Policy.bind("assemble.jarUp")); //$NON-NLS-1$
		String prefix = getPropertyFormat(PROPERTY_SOURCE) + '/' + getPropertyFormat(PROPERTY_ELEMENT_NAME);
		script.printZipTask(prefix + ".jar", prefix, false, null); //$NON-NLS-1$
		script.printDeleteTask(prefix, null, null);
		script.printTargetEnd();
	}

	private void generateGZipTarget() {
		script.println(
			"<move file=\"" //$NON-NLS-1$
				+ getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH)
				+ "\" tofile=\"" //$NON-NLS-1$
				+ getPropertyFormat(PROPERTY_TMP_DIR)
				+ '/' //$NON-NLS-1$
				+ getPropertyFormat(PROPERTY_COLLECTING_BASE)
				+ "/tmp.tar\"/>"); //$NON-NLS-1$
		script.printGZip(
			getPropertyFormat(PROPERTY_TMP_DIR) + '/' + getPropertyFormat(PROPERTY_COLLECTING_BASE) + "/tmp.tar", //$NON-NLS-1$ //$NON-NLS-2$
			getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH)); 
		List args = new ArrayList(2);
		args.add("-rf"); //$NON-NLS-1$
		args.add(getPropertyFormat(PROPERTY_TMP_DIR));
		script.printExecTask("rm", null, args, null); //$NON-NLS-1$
	}

	private void generatePrologue() {
		script.printProjectDeclaration("Assemble " + featureId, TARGET_MAIN, null); //$NON-NLS-1$  
		script.printProperty(PROPERTY_ARCHIVE_NAME, computeArchiveName());
		script.printProperty(PROPERTY_OS, configInfo.getOs());
		script.printProperty(PROPERTY_WS, configInfo.getWs());
		script.printProperty(PROPERTY_ARCH, configInfo.getArch());
		script.printProperty(PROPERTY_TMP_DIR, getPropertyFormat(PROPERTY_BUILD_DIRECTORY) + "/tmp"); //$NON-NLS-1$
		script.printProperty(PROPERTY_ECLIPSE_BASE, getPropertyFormat(PROPERTY_TMP_DIR) + '/' + getPropertyFormat(PROPERTY_COLLECTING_PLACE)); //$NON-NLS-1$ //$NON-NLS-2$
		script.printProperty(PROPERTY_DESTINATION_TEMP_FOLDER, getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + DEFAULT_PLUGIN_LOCATION); //$NON-NLS-1$
		script.printProperty(PROPERTY_ARCHIVE_FULLPATH, getPropertyFormat(PROPERTY_BASEDIR) + '/' + getPropertyFormat(PROPERTY_BUILD_LABEL) + '/' + getPropertyFormat(PROPERTY_ARCHIVE_NAME)); //$NON-NLS-1$ //$NON-NLS-2$
		generatePackagingTargets();
		script.printTargetDeclaration(TARGET_MAIN, null, null, null, null);
	}

	private void generateInitializationSteps() {
		script.printDeleteTask(getPropertyFormat(PROPERTY_TMP_DIR), null, null);
		script.printMkdirTask(getPropertyFormat(PROPERTY_TMP_DIR));
		script.printMkdirTask(getPropertyFormat(PROPERTY_BUILD_LABEL));
	}

	private void generateGatherBinPartsCalls() throws CoreException {
		for (int i = 0; i < plugins.length; i++) {
			BundleDescription plugin = plugins[i];
			String placeToGather = getLocation(plugin);
			script.printAntTask(DEFAULT_BUILD_SCRIPT_FILENAME, Utils.makeRelative(new Path(placeToGather), new Path(workingDirectory)).toOSString(), TARGET_GATHER_BIN_PARTS, null, null, null);
			generatePostProcessingSteps(plugin.getUniqueId(), plugin.getVersion().toString(), BUNDLE); 
		}
		
		for (int i = 0; i < features.length; i++) {
			IFeature feature = features[i];
			String placeToGather = feature.getURL().getPath();
			int j = placeToGather.lastIndexOf(DEFAULT_FEATURE_FILENAME_DESCRIPTOR);
			if (j != -1)
				placeToGather = placeToGather.substring(0, j);
			Map properties = new HashMap(1);
			properties.put(PROPERTY_FEATURE_BASE, getPropertyFormat(PROPERTY_ECLIPSE_BASE));
			script.printAntTask(DEFAULT_BUILD_SCRIPT_FILENAME, Utils.makeRelative(new Path(placeToGather), new Path(workingDirectory)).toOSString(), TARGET_GATHER_BIN_PARTS, null, null, properties);
			generatePostProcessingSteps(feature.getVersionedIdentifier().getIdentifier(), feature.getVersionedIdentifier().getVersion().toString(), FEATURE);
		}
	}

	//generate the appropriate postProcessingCall, and return a flag indicating the shape of the result, after processing 
	private void generatePostProcessingSteps(String name, String version, byte type) {
		Properties currentProperties = type==BUNDLE ? pluginsPostProcessingSteps : featuresPostProcessingSteps;
		String style = currentProperties.getProperty(name);
		if(style==null)
			return;
			
		if (flat.equalsIgnoreCase(style)) {
			//do nothing
			return;
		}
		if (updateJar.equalsIgnoreCase(style)) {
			generateJarUpCall(name, version);
			return;
		}
	}

	private byte getFinalShape(String name, String version, byte type) {
		Properties currentProperties = type==BUNDLE ? pluginsPostProcessingSteps : featuresPostProcessingSteps;
		String style = currentProperties.getProperty(name);
		if (flat.equalsIgnoreCase(style)) {
			//do nothing
			return FOLDER;
		}
		if (updateJar.equalsIgnoreCase(style)) {
			return FILE;
		}
		return FOLDER;
	}
	
	private void generateJarUpCall(String name, String version) {
		Map properties = new HashMap(2);
		properties.put(PROPERTY_SOURCE, getPropertyFormat(PROPERTY_DESTINATION_TEMP_FOLDER));
		properties.put(PROPERTY_ELEMENT_NAME, name + '_' + version);
		script.printAntCallTask(TARGET_JARUP, null, properties);
	}
	
	private void generateEpilogue() {
		script.printTargetEnd();
		script.printProjectEnd();
		script.close();
	}

	public String getFilename() {
		return getTargetName() + ".xml"; //$NON-NLS-1$
	}

	public String getTargetName() {
		return DEFAULT_ASSEMBLE_NAME + (featureId.equals("") ? "" : ('.' + featureId)) + (configInfo.equals(Config.genericConfig()) ? "" : ('.' + configInfo.toStringReplacingAny(".", ANY_STRING))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}

	private void generateZipTarget() {
		final int parameterSize = 15;
		List parameters = new ArrayList(parameterSize + 1);
		for (int i = 0; i < plugins.length; i++) {
			parameters.add(getPropertyFormat(PROPERTY_COLLECTING_PLACE) + '/' + DEFAULT_PLUGIN_LOCATION + '/' + plugins[i].getUniqueId() + '_' + plugins[i].getVersion() + (getFinalShape(plugins[i].getUniqueId(), plugins[i].getVersion().toString(), BUNDLE) == FOLDER ? "/" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (i % parameterSize == 0) {
				createZipExecCommand(parameters);
				parameters.clear();
			}
		}
		if (!parameters.isEmpty()) {
			createZipExecCommand(parameters);
			parameters.clear();
		}
		
		if (!parameters.isEmpty()) {
			createZipExecCommand(parameters);
			parameters.clear();
		}

		for (int i = 0; i < features.length; i++) {
			parameters.add(getPropertyFormat(PROPERTY_COLLECTING_PLACE) + '/' + DEFAULT_FEATURE_LOCATION + '/' + features[i].getVersionedIdentifier().toString() +  (getFinalShape(plugins[i].getUniqueId(), plugins[i].getVersion().toString(), BUNDLE) == FOLDER ? "/" : "")); //$NON-NLS-1$ //$NON-NLS-2$
			if (i % parameterSize == 0) {
				createZipExecCommand(parameters);
				parameters.clear();
			}
		}
		if (!parameters.isEmpty()) {
			createZipExecCommand(parameters);
			parameters.clear();
		}

		createZipRootFileCommand();
}

	/**
	 *  Zip the root files
	 */
	private void createZipRootFileCommand() {
		if (! copyRootFile)
			return;
			
		List parameters = new ArrayList(1);
		parameters.add("-r -q ${zipargs} " + getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH) + " . "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		script.printExecTask("zip", getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + configInfo.toStringReplacingAny(".", ANY_STRING) + '/' + getPropertyFormat(PROPERTY_COLLECTING_BASE), parameters, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	
	}
	private void createZipExecCommand(List parameters) {
		parameters.add(0, "-r -q " + getPropertyFormat(PROPERTY_ZIP_ARGS) + " " + getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH)); //$NON-NLS-1$ //$NON-NLS-2$
		script.printExecTask("zip", getPropertyFormat(PROPERTY_TMP_DIR) + '/' + getPropertyFormat(PROPERTY_COLLECTING_BASE), parameters, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	protected String computeArchiveName() {
		return featureId + "-" + getPropertyFormat(PROPERTY_BUILD_ID_PARAM) + (configInfo.equals(Config.genericConfig()) ? "" : ("-" + configInfo.toStringReplacingAny(".", ANY_STRING))) + ".zip"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
	}

	public void generateTarTarget() {
		List parameters = new ArrayList(2);
		parameters.add("-r " + getPropertyFormat(PROPERTY_TMP_DIR) + '/' + getPropertyFormat(PROPERTY_COLLECTING_BASE)  + '/' + getPropertyFormat(PROPERTY_COLLECTING_PLACE) + '/' + configInfo.toStringReplacingAny(".", ANY_STRING) + "/eclipse " + getPropertyFormat(PROPERTY_TMP_DIR) + '/' + getPropertyFormat(PROPERTY_COLLECTING_BASE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
		script.printExecTask("cp", getPropertyFormat(PROPERTY_BASEDIR), parameters, "Linux"); //$NON-NLS-1$ //$NON-NLS-2$
		
		parameters.clear();
		parameters.add("-rf " + getPropertyFormat(PROPERTY_TMP_DIR) + '/' + getPropertyFormat(PROPERTY_COLLECTING_BASE) + '/' + getPropertyFormat(PROPERTY_COLLECTING_PLACE) + '/' +  configInfo.toStringReplacingAny(".", ANY_STRING)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		script.printExecTask("rm", getPropertyFormat(PROPERTY_BASEDIR), parameters, "Linux"); //$NON-NLS-1$ //$NON-NLS-2$
		
		parameters.clear();
		parameters.add("-cvf " + getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH) + " eclipse "); //$NON-NLS-1$ //$NON-NLS-2$
		script.printExecTask("tar", getPropertyFormat(PROPERTY_TMP_DIR) + '/' +getPropertyFormat(PROPERTY_COLLECTING_BASE), parameters, "Linux"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}
}
