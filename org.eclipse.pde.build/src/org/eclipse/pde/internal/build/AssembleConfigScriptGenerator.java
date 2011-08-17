/*******************************************************************************
 * Copyright (c) 2000, 2007 IBM Corporation and others. All rights reserved.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: IBM - Initial API and implementation G&H Softwareentwicklung
 * GmbH - internationalization implementation (bug 150933) Prosyst - create
 * proper OSGi bundles (bug 174157)
 ******************************************************************************/
package org.eclipse.pde.internal.build;

import java.util.*;
import java.util.jar.JarFile;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.pde.build.Constants;
import org.eclipse.pde.internal.build.ant.*;
import org.eclipse.pde.internal.build.builder.ModelBuildScriptGenerator;
import org.eclipse.pde.internal.build.site.BuildTimeFeature;

/**
 * Generate an assemble script for a given feature and a given config. It
 * generates all the instruction to zip the listed plugins and features.
 */
public class AssembleConfigScriptGenerator extends AbstractScriptGenerator {
	protected String directory; // representing the directory where to generate the file
	protected String featureId;
	protected Config configInfo;
	protected BuildTimeFeature[] features; // the features that will be assembled
	protected BuildTimeFeature[] allFeatures; //the set of all the features that have been considered
	protected BundleDescription[] plugins;
	protected String filename;
	protected Collection rootFileProviders;
	protected String rootFolder = null;
	protected ArrayList addedByPermissions = new ArrayList(); //contains the list of files and folders that have been added to an archive by permission management

	private static final String PROPERTY_SOURCE = "source"; //$NON-NLS-1$
	private static final String PROPERTY_ELEMENT_NAME = "elementName"; //$NON-NLS-1$
	
	private static final byte BUNDLE = 0;
	private static final byte FEATURE = 1;

	protected String PROPERTY_ECLIPSE_PLUGINS = "eclipse.plugins"; //$NON-NLS-1$
	protected String PROPERTY_ECLIPSE_FEATURES = "eclipse.features"; //$NON-NLS-1$
	private boolean signJars;
	private boolean generateJnlp;

	private String archiveFormat;
	private boolean groupConfigs = false;
	private String product;
	private ProductFile productFile = null;
	protected ShapeAdvisor shapeAdvisor = null;
	private Boolean p2Bundles = null;

	public AssembleConfigScriptGenerator() {
		super();
	}

	public void initialize(String directoryName, String feature, Config configurationInformation, Collection elementList, Collection featureList, Collection allFeaturesList, Collection rootProviders) throws CoreException {
		this.directory = directoryName;
		this.featureId = feature;
		this.configInfo = configurationInformation;
		this.rootFileProviders = rootProviders != null ? rootProviders : new ArrayList(0);
		this.rootFolder = Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + configInfo.toStringReplacingAny(".", ANY_STRING) + '/' + Utils.getPropertyFormat(PROPERTY_COLLECTING_FOLDER); //$NON-NLS-1$
		this.features = new BuildTimeFeature[featureList.size()];
		featureList.toArray(this.features);

		this.allFeatures = new BuildTimeFeature[allFeaturesList.size()];
		allFeaturesList.toArray(this.allFeatures);

		this.plugins = new BundleDescription[elementList.size()];
		this.plugins = (BundleDescription[]) elementList.toArray(this.plugins);

		openScript(directoryName, getTargetName() + ".xml"); //$NON-NLS-1$
		shapeAdvisor = new ShapeAdvisor();
		shapeAdvisor.setForceUpdateJars(forceUpdateJarFormat);
	}

	private String computeIconsList() {
		String result = Utils.getPropertyFormat(PROPERTY_LAUNCHER_ICONS);
		if (productFile == null)
			return result;
		String[] icons = productFile.getIcons();
		for (int i = 0; i < icons.length; i++) {
			String location = findFile(icons[i], true);
			if (location != null)
				result += ", " + Utils.getPropertyFormat(PROPERTY_BASEDIR) + '/' + location; //$NON-NLS-1$
			else {
				result += ", " + Utils.getPropertyFormat(PROPERTY_BUILD_DIRECTORY) + '/' + DEFAULT_PLUGIN_LOCATION + '/' + icons[i]; //$NON-NLS-1$
				result += ", " + Utils.getPropertyFormat(PROPERTY_BUILD_DIRECTORY) + '/' + DEFAULT_FEATURE_LOCATION + '/' + icons[i]; //$NON-NLS-1$
			}
		}
		return result;
	}

	public void generate() {
		try {
			productFile = loadProduct(product);
		} catch (CoreException e) {
			//ignore
		}
		generatePrologue();
		generateInitializationSteps();
		generateGatherBinPartsCalls();
		if (embeddedSource)
			generateGatherSourceCalls();
		generatePostProcessingSteps();
		generateBrandingCalls();
		generateP2Steps();
		generateArchivingSteps();
		generateEpilogue();
	}

	/**
	 * 
	 */
	private void generateBrandingCalls() {
		script.printBrandTask(rootFolder, computeIconsList(), Utils.getPropertyFormat(PROPERTY_LAUNCHER_NAME), Utils.getPropertyFormat(PROPERTY_OS));
	}

	private void generateP2Steps() {
		if (!haveP2Bundles())
			return;
		if (rootFileProviders.size() == 0 && features.length == 0 && plugins.length == 0)
			return;
		script.printAntCallTask(TARGET_P2_METADATA, true, null);
	}

	private void generateArchivingSteps() {
		Map properties = new HashMap();
		properties.put(PROPERTY_ROOT_FOLDER, rootFolder);
		printCustomAssemblyAntCall(PROPERTY_PRE + "archive", properties); //$NON-NLS-1$

		if (FORMAT_FOLDER.equalsIgnoreCase(archiveFormat)) {
			generateMoveRootFiles();
			return;
		}

		if (FORMAT_ZIP.equalsIgnoreCase(archiveFormat)) {
			generateZipTarget();
			return;
		}

		if (FORMAT_ANTZIP.equalsIgnoreCase(archiveFormat)) {
			generateAntZipTarget();
			return;
		}

		if (FORMAT_ANTTAR.equalsIgnoreCase(archiveFormat)) {
			generateAntTarTarget();
			return;
		}

		if (FORMAT_TAR.equalsIgnoreCase(archiveFormat)) {
			generateTarGZTasks(true);
			return;
		}
	}

	private void generateMoveRootFiles() {
		if (rootFileProviders.size() == 0)
			return;

		for (Iterator iter = rootFileProviders.iterator(); iter.hasNext();) {
			Object object = iter.next();
			if (object instanceof BuildTimeFeature) {
				Properties featureProperties = null;
				try {
					featureProperties = AbstractScriptGenerator.readProperties(new Path(((BuildTimeFeature) object).getURL().getFile()).removeLastSegments(1).toOSString(), PROPERTIES_FILE, IStatus.OK);
					Utils.generatePermissions(featureProperties, configInfo, PROPERTY_ECLIPSE_BASE, script);
				} catch (CoreException e) {
					//do nothing
				}
			}
		}

		if (Platform.getOS().equals("win32")) { //$NON-NLS-1$
			FileSet[] rootFiles = new FileSet[1];
			rootFiles[0] = new FileSet(rootFolder, null, "**/**", null, null, null, null); //$NON-NLS-1$
			script.printMoveTask(Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE), rootFiles, false);
			script.printDeleteTask(Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + configInfo.toStringReplacingAny(".", ANY_STRING), null, null); //$NON-NLS-1$
		} else {
			List params = new ArrayList(3);
			params.add("-R"); //$NON-NLS-1$
			params.add("."); //$NON-NLS-1$
			params.add('\'' + Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '\'');
			String rootFileFolder = Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + configInfo.toStringReplacingAny(".", ANY_STRING); //$NON-NLS-1$
			script.printExecTask("cp", rootFileFolder + '/' + Utils.getPropertyFormat(PROPERTY_COLLECTING_FOLDER), params, null); //$NON-NLS-1$
			script.printDeleteTask(rootFileFolder, null, null);
		}
	}

	protected void generateGatherSourceCalls() {
		Map properties = new HashMap(1);
		properties.put(PROPERTY_DESTINATION_TEMP_FOLDER, Utils.getPropertyFormat(PROPERTY_ECLIPSE_PLUGINS));

		for (int i = 0; i < plugins.length; i++) {
			BundleDescription plugin = plugins[i];
			String placeToGather = getLocation(plugin);

			script.printAntTask(DEFAULT_BUILD_SCRIPT_FILENAME, Utils.makeRelative(new Path(placeToGather), new Path(workingDirectory)).toOSString(), TARGET_GATHER_SOURCES, null, null, properties);

			Properties bundleProperties = (Properties) plugin.getUserObject();
			//Source code for plugins with . on the classpath must be put in a folder in the final jar.
			if (bundleProperties.get(WITH_DOT) == Boolean.TRUE) {
				String targetLocation = Utils.getPropertyFormat(PROPERTY_ECLIPSE_PLUGINS) + '/' + ModelBuildScriptGenerator.getNormalizedName(plugin);
				String targetLocationSrc = targetLocation + "/src"; //$NON-NLS-1$

				//Find the source zip where it has been gathered and extract it in a folder  
				script.println("<unzip dest=\"" + AntScript.getEscaped(targetLocationSrc) + "\">"); //$NON-NLS-1$//$NON-NLS-2$
				script.println("\t<fileset dir=\"" + AntScript.getEscaped(targetLocation) + "\" includes=\"**/*src.zip\" casesensitive=\"false\"/>"); //$NON-NLS-1$//$NON-NLS-2$
				script.println("</unzip>"); //$NON-NLS-1$

				//	Delete the source zip where it has been gathered since we extracted it
				script.printDeleteTask(null, null, new FileSet[] {new FileSet(targetLocation, null, "**/*src.zip", null, null, null, "false")}); //$NON-NLS-1$ //$NON-NLS-2$//$NON-bNLS-3$
			}
		}

		properties = new HashMap(1);
		properties.put(PROPERTY_FEATURE_BASE, Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE));
		for (int i = 0; i < features.length; i++) {
			BuildTimeFeature feature = features[i];
			String placeToGather = feature.getURL().getPath();
			int j = placeToGather.lastIndexOf(Constants.FEATURE_FILENAME_DESCRIPTOR);
			if (j != -1)
				placeToGather = placeToGather.substring(0, j);
			script.printAntTask(DEFAULT_BUILD_SCRIPT_FILENAME, Utils.makeRelative(new Path(placeToGather), new Path(workingDirectory)).toOSString(), TARGET_GATHER_SOURCES, null, null, properties);
		}
	}

	protected void generatePackagingTargets() {
		String fileName = Utils.getPropertyFormat(PROPERTY_SOURCE) + '/' + Utils.getPropertyFormat(PROPERTY_ELEMENT_NAME);
		String fileExists = Utils.getPropertyFormat(PROPERTY_SOURCE) + '/' + Utils.getPropertyFormat(PROPERTY_ELEMENT_NAME) + "_exists"; //$NON-NLS-1$

		script.printComment("Beginning of the jarUp task"); //$NON-NLS-1$
		script.printTargetDeclaration(TARGET_JARUP, null, null, null, Messages.assemble_jarUp);
		script.printAvailableTask(fileExists, fileName);
		Map params = new HashMap(2);
		params.put(PROPERTY_SOURCE, Utils.getPropertyFormat(PROPERTY_SOURCE));
		params.put(PROPERTY_ELEMENT_NAME, Utils.getPropertyFormat(PROPERTY_ELEMENT_NAME));
		script.printAvailableTask(PROPERTY_JARING_MANIFEST, fileName + '/' + JarFile.MANIFEST_NAME);
		script.printConditionIsSet(PROPERTY_JARING_TASK, TARGET_JARING, PROPERTY_JARING_MANIFEST, TARGET_JARING + "_NoManifest"); //$NON-NLS-1$
		script.printAntCallTask(Utils.getPropertyFormat(PROPERTY_JARING_TASK), true, params);
		script.printTargetEnd();

		script.printTargetDeclaration(TARGET_JARING, null, fileExists, null, null);
		script.printJarTask(fileName + ".jar", fileName, fileName + '/' + JarFile.MANIFEST_NAME, "skip"); //$NON-NLS-1$ //$NON-NLS-2$
		script.printDeleteTask(fileName, null, null);
		script.printTargetEnd();

		script.printTargetDeclaration(TARGET_JARING + "_NoManifest", null, fileExists, null, null); //$NON-NLS-1$
		script.printJarTask(fileName + ".jar", fileName, null, "merge"); //$NON-NLS-1$ //$NON-NLS-2$
		script.printDeleteTask(fileName, null, null);
		script.printTargetEnd();
		script.printComment("End of the jarUp task"); //$NON-NLS-1$

		script.printComment("Beginning of the jar signing  target"); //$NON-NLS-1$
		script.printTargetDeclaration(TARGET_JARSIGNING, null, null, null, Messages.sign_Jar);
		printCustomAssemblyAntCall(PROPERTY_PRE + TARGET_JARSIGNING, null);
		if (generateJnlp)
			script.printProperty(PROPERTY_UNSIGN, "true"); //$NON-NLS-1$
		script.println("<eclipse.jarProcessor sign=\"" + Utils.getPropertyFormat(PROPERTY_SIGN) + "\" pack=\"" + Utils.getPropertyFormat(PROPERTY_PACK) + "\" unsign=\"" + Utils.getPropertyFormat(PROPERTY_UNSIGN) + "\" jar=\"" + fileName + ".jar" + "\" alias=\"" + Utils.getPropertyFormat(PROPERTY_SIGN_ALIAS) + "\" keystore=\"" + Utils.getPropertyFormat(PROPERTY_SIGN_KEYSTORE) + "\" storepass=\"" + Utils.getPropertyFormat(PROPERTY_SIGN_STOREPASS) + "\"/>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ 
		script.printTargetEnd();
		script.printComment("End of the jarUp task"); //$NON-NLS-1$
	}

	protected void generateGZipTarget(boolean assembling) {
		//during the assemble stage, only zip if we aren't running the packager
		script.printTargetDeclaration(TARGET_GZIP_RESULTS, null, null, assembling ? PROPERTY_RUN_PACKAGER : null, null);
		script.println("<move file=\"" //$NON-NLS-1$
				+ Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH) + "\" tofile=\"" //$NON-NLS-1$
				+ Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP) + '/' + Utils.getPropertyFormat(PROPERTY_COLLECTING_FOLDER) + "/tmp.tar\"/>"); //$NON-NLS-1$
		script.printGZip(Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP) + '/' + Utils.getPropertyFormat(PROPERTY_COLLECTING_FOLDER) + "/tmp.tar", //$NON-NLS-1$ 
				Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH));
		script.printTargetEnd();
	}

	protected void generatePrologue() {
		script.printProjectDeclaration("Assemble " + featureId, TARGET_MAIN, null); //$NON-NLS-1$  
		script.printProperty(PROPERTY_ARCHIVE_NAME, computeArchiveName());
		script.printProperty(PROPERTY_OS, configInfo.getOs());
		script.printProperty(PROPERTY_WS, configInfo.getWs());
		script.printProperty(PROPERTY_ARCH, configInfo.getArch());
		script.printProperty(PROPERTY_SIGN, (signJars ? Boolean.TRUE : Boolean.FALSE).toString());
		script.printProperty(PROPERTY_ASSEMBLY_TMP, Utils.getPropertyFormat(PROPERTY_BUILD_DIRECTORY) + "/tmp"); //$NON-NLS-1$
		script.printProperty(PROPERTY_ECLIPSE_BASE, Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP) + '/' + Utils.getPropertyFormat(PROPERTY_COLLECTING_FOLDER));
		script.printProperty(PROPERTY_ECLIPSE_PLUGINS, Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + DEFAULT_PLUGIN_LOCATION);
		script.printProperty(PROPERTY_ECLIPSE_FEATURES, Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + DEFAULT_FEATURE_LOCATION);
		script.printProperty(PROPERTY_ARCHIVE_FULLPATH, Utils.getPropertyFormat(PROPERTY_BASEDIR) + '/' + Utils.getPropertyFormat(PROPERTY_BUILD_LABEL) + '/' + Utils.getPropertyFormat(PROPERTY_ARCHIVE_NAME));

		script.printAvailableTask(PROPERTY_CUSTOM_ASSEMBLY, "${builder}/customAssembly.xml", "${builder}/customAssembly.xml"); //$NON-NLS-1$ //$NON-NLS-2$

		generateCustomGatherMacro();

		if (productFile != null && productFile.getLauncherName() != null)
			script.printProperty(PROPERTY_LAUNCHER_NAME, productFile.getLauncherName());
		script.printProperty(PROPERTY_TAR_ARGS, ""); //$NON-NLS-1$
		generatePackagingTargets();
		script.printTargetDeclaration(TARGET_MAIN, null, null, null, null);
	}

	private void generateCustomGatherMacro() {
		script.println();
		List attributes = new ArrayList(2);
		attributes.add("dir"); //$NON-NLS-1$
		attributes.add("propertyName"); //$NON-NLS-1$
		attributes.add("propertyValue"); //$NON-NLS-1$
		attributes.add("subFolder"); //$NON-NLS-1$
		attributes.add(PROPERTY_PROJECT_NAME);
		script.printMacroDef(PROPERTY_CUSTOM_GATHER, attributes);

		Map params = new HashMap();
		params.put("@{propertyName}", "@{propertyValue}"); //$NON-NLS-1$//$NON-NLS-2$
		script.printAntTask(DEFAULT_BUILD_SCRIPT_FILENAME, "@{dir}", TARGET_GATHER_BIN_PARTS, null, null, params); //$NON-NLS-1$

		params.put(PROPERTY_PROJECT_LOCATION, "${basedir}/@{dir}"); //$NON-NLS-1$
		params.put(PROPERTY_PROJECT_NAME, "@{projectName}"); //$NON-NLS-1$
		params.put(PROPERTY_TARGET_FOLDER, "@{propertyValue}@{subFolder}"); //$NON-NLS-1$
		printCustomAssemblyAntCall(TARGET_GATHER_BIN_PARTS, params);

		script.printEndMacroDef();
		script.println();
	}

	private void printCustomGatherCall(String fullName, String dir, String propertyName, String propertyValue, String subFolder) {
		script.println("<" + PROPERTY_CUSTOM_GATHER + " dir=\"" + dir + "\" projectName=\"" + fullName + "\" propertyName=\"" + propertyName + "\" propertyValue=\"" + propertyValue + "\" subFolder=\"" + (subFolder != null ? subFolder : "") + "\" />"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
	}

	private void generateInitializationSteps() {
		if (BundleHelper.getDefault().isDebugging()) {
			script.printEchoTask("basedir : " + Utils.getPropertyFormat(PROPERTY_BASEDIR)); //$NON-NLS-1$
			script.printEchoTask("assemblyTempDir : " + Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP)); //$NON-NLS-1$
			script.printEchoTask("eclipse.base : " + Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE)); //$NON-NLS-1$
			script.printEchoTask("collectingFolder : " + Utils.getPropertyFormat(PROPERTY_COLLECTING_FOLDER)); //$NON-NLS-1$
			script.printEchoTask("archivePrefix : " + Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX)); //$NON-NLS-1$
		}

		script.println("<condition property=\"" + PROPERTY_PLUGIN_ARCHIVE_PREFIX + "\" value=\"" + DEFAULT_PLUGIN_LOCATION + "\">"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		script.println("\t<equals arg1=\"" + Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX) + "\"  arg2=\"\" trim=\"true\"/>"); //$NON-NLS-1$ //$NON-NLS-2$ 
		script.println("</condition>"); //$NON-NLS-1$
		script.printProperty(PROPERTY_PLUGIN_ARCHIVE_PREFIX, Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX) + '/' + DEFAULT_PLUGIN_LOCATION);

		script.println();
		script.println("<condition property=\"" + PROPERTY_FEATURE_ARCHIVE_PREFIX + "\" value=\"" + DEFAULT_FEATURE_LOCATION + "\">"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		script.println("\t<equals arg1=\"" + Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX) + "\"  arg2=\"\" trim=\"true\"/>"); //$NON-NLS-1$ //$NON-NLS-2$ 
		script.println("</condition>"); //$NON-NLS-1$
		script.printProperty(PROPERTY_FEATURE_ARCHIVE_PREFIX, Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX) + '/' + DEFAULT_FEATURE_LOCATION);

		script.println();

		script.printDirName(PROPERTY_ARCHIVE_PARENT, Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH));
		script.printMkdirTask(Utils.getPropertyFormat(PROPERTY_ARCHIVE_PARENT));
		script.printMkdirTask(Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP));
		script.printMkdirTask(Utils.getPropertyFormat(PROPERTY_BUILD_LABEL));
	}

	protected void generatePostProcessingSteps() {
		printCustomAssemblyAntCall(PROPERTY_POST + TARGET_GATHER_BIN_PARTS, null);
		for (int i = 0; i < plugins.length; i++) {
			BundleDescription plugin = plugins[i];
			generatePostProcessingSteps(plugin.getSymbolicName(), plugin.getVersion().toString(), (String) shapeAdvisor.getFinalShape(plugin)[1], BUNDLE);
		}

		for (int i = 0; i < features.length; i++) {
			BuildTimeFeature feature = features[i];
			generatePostProcessingSteps(feature.getId(), feature.getVersion(), (String) shapeAdvisor.getFinalShape(feature)[1], FEATURE);
		}
		printCustomAssemblyAntCall(PROPERTY_POST + TARGET_JARUP, null);
	}

	protected void generateGatherBinPartsCalls() {
		for (int i = 0; i < plugins.length; i++) {
			BundleDescription plugin = plugins[i];
			String placeToGather = getLocation(plugin);
			printCustomGatherCall(ModelBuildScriptGenerator.getNormalizedName(plugin), Utils.makeRelative(new Path(placeToGather), new Path(workingDirectory)).toOSString(), PROPERTY_DESTINATION_TEMP_FOLDER, Utils.getPropertyFormat(PROPERTY_ECLIPSE_PLUGINS), null);
		}

		for (int i = 0; i < features.length; i++) {
			BuildTimeFeature feature = features[i];
			String placeToGather = feature.getURL().getPath();
			int j = placeToGather.lastIndexOf(Constants.FEATURE_FILENAME_DESCRIPTOR);
			if (j != -1)
				placeToGather = placeToGather.substring(0, j);
			String featureFullName = feature.getId() + "_" + feature.getVersion(); //$NON-NLS-1$
			printCustomGatherCall(featureFullName, Utils.makeRelative(new Path(placeToGather), new Path(workingDirectory)).toOSString(), PROPERTY_FEATURE_BASE, Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE), '/' + DEFAULT_FEATURE_LOCATION);
		}

		//This will generate gather.bin.parts call to features that provides files for the root
		for (Iterator iter = rootFileProviders.iterator(); iter.hasNext();) {
			BuildTimeFeature feature = (BuildTimeFeature) iter.next();
			String placeToGather = feature.getURL().getPath();
			int j = placeToGather.lastIndexOf(Constants.FEATURE_FILENAME_DESCRIPTOR);
			if (j != -1)
				placeToGather = placeToGather.substring(0, j);
			String featureFullName = feature.getId() + "_" + feature.getVersion(); //$NON-NLS-1$
			printCustomGatherCall(featureFullName, Utils.makeRelative(new Path(placeToGather), new Path(workingDirectory)).toOSString(), PROPERTY_FEATURE_BASE, Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE), '/' + DEFAULT_FEATURE_LOCATION);
		}
	}

	private void generateSignJarCall(String name, String version, byte type) {
		if (!signJars)
			return;
		Map properties = new HashMap(2);
		properties.put(PROPERTY_SOURCE, type == BUNDLE ? Utils.getPropertyFormat(PROPERTY_ECLIPSE_PLUGINS) : Utils.getPropertyFormat(PROPERTY_ECLIPSE_FEATURES));
		properties.put(PROPERTY_ELEMENT_NAME, name + '_' + version);
		script.printAntCallTask(TARGET_JARSIGNING, true, properties);
	}

	//generate the appropriate postProcessingCall
	private void generatePostProcessingSteps(String name, String version, String style, byte type) {
		if (ShapeAdvisor.FOLDER.equalsIgnoreCase(style))
			return;
		if (ShapeAdvisor.FILE.equalsIgnoreCase(style)) {
			generateJarUpCall(name, version, type);
			generateSignJarCall(name, version, type);
			generateJNLPCall(name, version, type);
			return;
		}
	}

	private void generateJNLPCall(String name, String version, byte type) {
		if (generateJnlp == false)
			return;
		if (type != FEATURE)
			return;

		String dir = type == BUNDLE ? Utils.getPropertyFormat(PROPERTY_ECLIPSE_PLUGINS) : Utils.getPropertyFormat(PROPERTY_ECLIPSE_FEATURES);
		String location = dir + '/' + name + '_' + version + ".jar"; //$NON-NLS-1$
		script.println("<eclipse.jnlpGenerator feature=\"" + AntScript.getEscaped(location) + "\"  codebase=\"" + Utils.getPropertyFormat(PROPERTY_JNLP_CODEBASE) + "\" j2se=\"" + Utils.getPropertyFormat(PROPERTY_JNLP_J2SE) + "\" locale=\"" + Utils.getPropertyFormat(PROPERTY_JNLP_LOCALE) + "\" generateOfflineAllowed=\"" + Utils.getPropertyFormat(PROPERTY_JNLP_GENOFFLINE) + "\" configInfo=\"" + Utils.getPropertyFormat(PROPERTY_JNLP_CONFIGS) + "\"/>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
	}

	private void generateJarUpCall(String name, String version, byte type) {
		Map properties = new HashMap(2);
		properties.put(PROPERTY_SOURCE, type == BUNDLE ? Utils.getPropertyFormat(PROPERTY_ECLIPSE_PLUGINS) : Utils.getPropertyFormat(PROPERTY_ECLIPSE_FEATURES));
		properties.put(PROPERTY_ELEMENT_NAME, name + '_' + version);
		script.printAntCallTask(TARGET_JARUP, true, properties);
	}

	private void generateEpilogue() {
		if (!FORMAT_FOLDER.equalsIgnoreCase(archiveFormat))
			script.printDeleteTask(Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP), null, null);
		script.printTargetEnd();
		if (FORMAT_TAR.equalsIgnoreCase(archiveFormat))
			generateGZipTarget(true);

		generateCustomAssemblyTarget();
		generateMetadataTarget();

		script.printProjectEnd();
		script.close();
		script = null;
	}

	public String getTargetName() {
		String config = getTargetConfig();
		return DEFAULT_ASSEMBLE_NAME + '.' + getTargetElement() + (config.length() > 0 ? "." : "") + config; //$NON-NLS-1$ //$NON-NLS-2$
	}

	public String getTargetConfig() {
		return (configInfo.equals(Config.genericConfig()) ? "" : configInfo.toStringReplacingAny(".", ANY_STRING)); //$NON-NLS-1$ //$NON-NLS-2$
	}

	public String getTargetElement() {
		return (featureId.equals("") ? "" : featureId); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private void printCustomAssemblyAntCall(String customTarget, Map properties) {
		Map params = (properties != null) ? new HashMap(properties) : new HashMap(1);
		params.put(PROPERTY_CUSTOM_TARGET, customTarget);
		script.printAntCallTask(TARGET_CUSTOM_ASSEMBLY, true, params);
	}

	private void generateCustomAssemblyTarget() {
		script.printTargetDeclaration(TARGET_CUSTOM_ASSEMBLY, null, PROPERTY_CUSTOM_ASSEMBLY, null, null);
		script.printAntTask(Utils.getPropertyFormat(PROPERTY_CUSTOM_ASSEMBLY), null, Utils.getPropertyFormat(PROPERTY_CUSTOM_TARGET), null, TRUE, null);
		script.printTargetEnd();
	}

	private void generateMetadataTarget() {
		if (haveP2Bundles()) {
			script.printTargetDeclaration(TARGET_P2_METADATA, null, TARGET_P2_METADATA, null, null);
			script.printProperty(PROPERTY_P2_APPEND, "true"); //$NON-NLS-1$
			script.printProperty(PROPERTY_P2_COMPRESS, "false"); //$NON-NLS-1$
			script.printProperty(PROPERTY_P2_METADATA_REPO_NAME, ""); //$NON-NLS-1$
			script.printProperty(PROPERTY_P2_ARTIFACT_REPO_NAME, ""); //$NON-NLS-1$

			if (havePDEUIState()) {
				//during feature export we need to override the "mode"
				printP2GenerationModeCondition();
			}
			script.print("<p2.generator "); //$NON-NLS-1$
			script.printAttribute("source", Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE), true); //$NON-NLS-1$
			script.printAttribute("append", Utils.getPropertyFormat(PROPERTY_P2_APPEND), true); //$NON-NLS-1$
			script.printAttribute("flavor", Utils.getPropertyFormat(PROPERTY_P2_FLAVOR), true); //$NON-NLS-1$
			script.printAttribute("compress", Utils.getPropertyFormat(PROPERTY_P2_COMPRESS), true); //$NON-NLS-1$ 
			script.printAttribute("metadataRepository", Utils.getPropertyFormat(PROPERTY_P2_METADATA_REPO), true); //$NON-NLS-1$ 
			script.printAttribute("artifactRepository", Utils.getPropertyFormat(PROPERTY_P2_ARTIFACT_REPO), true); //$NON-NLS-1$ 
			script.printAttribute("metadataRepositoryName", Utils.getPropertyFormat(PROPERTY_P2_METADATA_REPO_NAME), true); //$NON-NLS-1$
			script.printAttribute("artifactRepositoryName", Utils.getPropertyFormat(PROPERTY_P2_ARTIFACT_REPO_NAME), true); //$NON-NLS-1$
			script.printAttribute("publishArtifacts", Utils.getPropertyFormat(PROPERTY_P2_PUBLISH_ARTIFACTS), true); //$NON-NLS-1$
			script.printAttribute("p2OS", configInfo.getOs(), true); //$NON-NLS-1$
			if (!havePDEUIState() || rootFileProviders.size() > 0)
				script.printAttribute("mode", "incremental", true); //$NON-NLS-1$ //$NON-NLS-2$
			else
				script.printAttribute("mode", Utils.getPropertyFormat(PROPERTY_P2_GENERATION_MODE), true); //$NON-NLS-1$
			script.println("/>"); //$NON-NLS-1$

			if (rootFileProviders.size() > 0) {
				script.print("<p2.generator "); //$NON-NLS-1$
				script.printAttribute("config", rootFolder, true); //$NON-NLS-1$
				script.printAttribute("append", Utils.getPropertyFormat(PROPERTY_P2_APPEND), true); //$NON-NLS-1$ 
				script.printAttribute("flavor", Utils.getPropertyFormat(PROPERTY_P2_FLAVOR), true); //$NON-NLS-1$
				script.printAttribute("compress", Utils.getPropertyFormat(PROPERTY_P2_COMPRESS), true); //$NON-NLS-1$ 
				script.printAttribute("metadataRepository", Utils.getPropertyFormat(PROPERTY_P2_METADATA_REPO), true); //$NON-NLS-1$ 
				script.printAttribute("artifactRepository", Utils.getPropertyFormat(PROPERTY_P2_ARTIFACT_REPO), true); //$NON-NLS-1$ 
				script.printAttribute("metadataRepositoryName", Utils.getPropertyFormat(PROPERTY_P2_METADATA_REPO_NAME), true); //$NON-NLS-1$
				script.printAttribute("artifactRepositoryName", Utils.getPropertyFormat(PROPERTY_P2_ARTIFACT_REPO_NAME), true); //$NON-NLS-1$
				script.printAttribute("launcherConfig", configInfo.toString(), true); //$NON-NLS-1$
				script.printAttribute("p2OS", configInfo.getOs(), true); //$NON-NLS-1$
				script.printAttribute("publishArtifacts", Utils.getPropertyFormat(PROPERTY_P2_PUBLISH_ARTIFACTS), true); //$NON-NLS-1$ 
				if (!havePDEUIState())
					script.printAttribute("mode", "incremental", true); //$NON-NLS-1$ //$NON-NLS-2$
				else
					script.printAttribute("mode", Utils.getPropertyFormat(PROPERTY_P2_GENERATION_MODE), true); //$NON-NLS-1$
				if (productFile != null) {
					script.printAttribute("exe", rootFolder + '/' + Utils.getPropertyFormat(PROPERTY_LAUNCHER_NAME), true); //$NON-NLS-1$
					script.printAttribute("productFile", productFile.getLocation(), true); //$NON-NLS-1$
				}
				script.println("/>"); //$NON-NLS-1$
			}

			script.printTargetEnd();
		}
	}

	protected void printP2GenerationModeCondition() {
		// "final" if not running packager and we are overriding, else "incremental"
		script.print("<condition"); //$NON-NLS-1$
		script.printAttribute("property", PROPERTY_P2_GENERATION_MODE, true); //$NON-NLS-1$
		script.printAttribute("value", "final", true); //$NON-NLS-1$ //$NON-NLS-2$
		script.printAttribute("else", "incremental", false); //$NON-NLS-1$ //$NON-NLS-2$
		script.println(">"); //$NON-NLS-1$
		script.println("\t<and>"); //$NON-NLS-1$
		script.println("\t\t<not>"); //$NON-NLS-1$
		script.println("\t\t\t<isset property=\"runPackager\"/>"); //$NON-NLS-1$
		script.println("\t\t</not>"); //$NON-NLS-1$
		script.println("\t\t<isset property=\"" + PROPERTY_P2_FINAL_MODE_OVERRIDE + "\"/>"); //$NON-NLS-1$ //$NON-NLS-2$
		script.println("\t</and>"); //$NON-NLS-1$
		script.printEndTag("condition"); //$NON-NLS-1$
	}

	public boolean haveP2Bundles() {
		if (p2Bundles != null)
			return p2Bundles.booleanValue();

		p2Bundles = Boolean.valueOf(loadP2Class());
		return p2Bundles.booleanValue();
	}

	private void generateZipTarget() {
		final int parameterSize = 15;
		List parameters = new ArrayList(parameterSize + 1);
		for (int i = 0; i < plugins.length; i++) {
			parameters.add(Utils.getPropertyFormat(PROPERTY_PLUGIN_ARCHIVE_PREFIX) + '/' + (String) shapeAdvisor.getFinalShape(plugins[i])[0]);
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
			parameters.add(Utils.getPropertyFormat(PROPERTY_FEATURE_ARCHIVE_PREFIX) + '/' + (String) shapeAdvisor.getFinalShape(features[i])[0]);
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
		if (rootFileProviders.size() == 0)
			return;

		List parameters = new ArrayList(1);
		parameters.add("-r -q ${zipargs} '" + Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH) + "' . "); //$NON-NLS-1$ //$NON-NLS-2$
		script.printExecTask("zip", Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + configInfo.toStringReplacingAny(".", ANY_STRING), parameters, null); //$NON-NLS-1$ //$NON-NLS-2$ 
	}

	private void createZipExecCommand(List parameters) {
		parameters.add(0, "-r -q " + Utils.getPropertyFormat(PROPERTY_ZIP_ARGS) + " '" + Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH) + '\''); //$NON-NLS-1$ //$NON-NLS-2$
		script.printExecTask("zip", Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP), parameters, null); //$NON-NLS-1$ 
	}

	protected String computeArchiveName() {
		String extension = (FORMAT_TAR.equalsIgnoreCase(archiveFormat) || FORMAT_ANTTAR.equalsIgnoreCase(archiveFormat)) ? ".tar.gz" : ".zip"; //$NON-NLS-1$ //$NON-NLS-2$
		return featureId + "-" + Utils.getPropertyFormat(PROPERTY_BUILD_ID_PARAM) + (configInfo.equals(Config.genericConfig()) ? "" : ("-" + configInfo.toStringReplacingAny(".", ANY_STRING))) + extension; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	public void generateTarGZTasks(boolean assembling) {
		//This task only supports creation of archive with eclipse at the root 
		//Need to do the copy using cp because of the link
		List parameters = new ArrayList(2);
		if (rootFileProviders.size() > 0) {
			parameters.add("-r '" + Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP) + '/' + Utils.getPropertyFormat(PROPERTY_COLLECTING_FOLDER) + '/' + configInfo.toStringReplacingAny(".", ANY_STRING) + '/' + Utils.getPropertyFormat(PROPERTY_COLLECTING_FOLDER) + "' '" + Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP) + '\''); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$  
			script.printExecTask("cp", Utils.getPropertyFormat(PROPERTY_BASEDIR), parameters, null); //$NON-NLS-1$

			parameters.clear();
			parameters.add("-rf '" + Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP) + '/' + Utils.getPropertyFormat(PROPERTY_COLLECTING_FOLDER) + '/' + configInfo.toStringReplacingAny(".", ANY_STRING) + '\''); //$NON-NLS-1$ //$NON-NLS-2$
			script.printExecTask("rm", Utils.getPropertyFormat(PROPERTY_BASEDIR), parameters, null); //$NON-NLS-1$
		}
		parameters.clear();
		String tarArgs = assembling ? "-cvf '" : "-rvf '"; //$NON-NLS-1$//$NON-NLS-2$
		parameters.add(Utils.getPropertyFormat(PROPERTY_TAR_ARGS) + tarArgs + Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH) + "' " + Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX) + ' '); //$NON-NLS-1$
		script.printExecTask("tar", Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP), parameters, null); //$NON-NLS-1$ 

		script.printAntCallTask(TARGET_GZIP_RESULTS, true, null);

		List args = new ArrayList(2);
		args.add("-rf"); //$NON-NLS-1$
		args.add('\'' + Utils.getPropertyFormat(PROPERTY_ASSEMBLY_TMP) + '\'');
		script.printExecTask("rm", null, args, null); //$NON-NLS-1$
	}

	//TODO this code and the generateAntTarTarget() should be refactored using a factory or something like that.
	protected void generateAntZipTarget() {
		FileSet[] filesPlugins = new FileSet[plugins.length];
		for (int i = 0; i < plugins.length; i++) {
			Object[] shape = shapeAdvisor.getFinalShape(plugins[i]);
			filesPlugins[i] = new ZipFileSet(Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + DEFAULT_PLUGIN_LOCATION + '/' + (String) shape[0], shape[1] == ShapeAdvisor.FILE, null, null, null, null, null, Utils.getPropertyFormat(PROPERTY_PLUGIN_ARCHIVE_PREFIX) + '/' + (String) shape[0], null, null);
		}
		if (plugins.length != 0)
			script.printZipTask(Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH), null, false, true, filesPlugins);

		FileSet[] filesFeatures = new FileSet[features.length];
		for (int i = 0; i < features.length; i++) {
			Object[] shape = shapeAdvisor.getFinalShape(features[i]);
			filesFeatures[i] = new ZipFileSet(Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + DEFAULT_FEATURE_LOCATION + '/' + (String) shape[0], shape[1] == ShapeAdvisor.FILE, null, null, null, null, null, Utils.getPropertyFormat(PROPERTY_FEATURE_ARCHIVE_PREFIX) + '/' + (String) shape[0], null, null);
		}
		if (features.length != 0)
			script.printZipTask(Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH), null, false, true, filesFeatures);

		if (rootFileProviders.size() == 0)
			return;

		if (groupConfigs) {
			List allConfigs = getConfigInfos();
			FileSet[] rootFiles = new FileSet[allConfigs.size()];
			int i = 0;
			for (Iterator iter = allConfigs.iterator(); iter.hasNext();) {
				Config elt = (Config) iter.next();
				rootFiles[i++] = new ZipFileSet(Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + elt.toStringReplacingAny(".", ANY_STRING), false, null, "**/**", null, null, null, elt.toStringReplacingAny(".", ANY_STRING), null, null); //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$
			}
			script.printZipTask(Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH), null, false, true, rootFiles);
		} else {
			FileSet[] permissionSets = generatePermissions(true);
			FileSet[] rootFiles = new FileSet[permissionSets.length + 1];
			String toExcludeFromArchive = Utils.getStringFromCollection(this.addedByPermissions, ","); //$NON-NLS-1$
			System.arraycopy(permissionSets, 0, rootFiles, 1, permissionSets.length);
			rootFiles[0] = new ZipFileSet(rootFolder, false, null, "**/**", null, toExcludeFromArchive, null, Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX), null, null); //$NON-NLS-1$
			script.printZipTask(Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH), null, false, true, rootFiles);
		}
	}

	protected FileSet[] generatePermissions(boolean zip) {
		String configInfix = configInfo.toString("."); //$NON-NLS-1$
		String prefixPermissions = ROOT_PREFIX + configInfix + '.' + PERMISSIONS + '.';
		String commonPermissions = ROOT_PREFIX + PERMISSIONS + '.';
		ArrayList fileSets = new ArrayList();

		for (Iterator iter = rootFileProviders.iterator(); iter.hasNext();) {
			Properties featureProperties = null;
			try {
				featureProperties = AbstractScriptGenerator.readProperties(new Path(((BuildTimeFeature) iter.next()).getURL().getFile()).removeLastSegments(1).toOSString(), PROPERTIES_FILE, IStatus.OK);
			} catch (CoreException e) {
				return new FileSet[0];
			}

			for (Iterator iter2 = featureProperties.entrySet().iterator(); iter2.hasNext();) {
				Map.Entry permission = (Map.Entry) iter2.next();
				String instruction = (String) permission.getKey();
				String parameters = (String) permission.getValue();
				String[] values = Utils.getArrayFromString(parameters);
				for (int i = 0; i < values.length; i++) {
					boolean isFile = !values[i].endsWith("/"); //$NON-NLS-1$
					if (instruction.startsWith(prefixPermissions)) {
						addedByPermissions.add(values[i]);
						if (zip)
							fileSets.add(new ZipFileSet(rootFolder + (isFile ? '/' + values[i] : ""), isFile, null, isFile ? null : values[i] + "/**", null, null, null, Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX) + (isFile ? '/' + values[i] : ""), null, instruction.substring(prefixPermissions.length()))); //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$
						else
							fileSets.add(new TarFileSet(rootFolder + (isFile ? '/' + values[i] : ""), isFile, null, isFile ? null : values[i] + "/**", null, null, null, Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX) + (isFile ? '/' + values[i] : ""), null, instruction.substring(prefixPermissions.length()))); //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$
						continue;
					}
					if (instruction.startsWith(commonPermissions)) {
						addedByPermissions.add(values[i]);
						if (zip)
							fileSets.add(new ZipFileSet(rootFolder + (isFile ? '/' + values[i] : ""), isFile, null, isFile ? null : values[i] + "/**", null, null, null, Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX) + (isFile ? '/' + values[i] : ""), null, instruction.substring(commonPermissions.length()))); //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$
						else
							fileSets.add(new TarFileSet(rootFolder + (isFile ? '/' + values[i] : ""), isFile, null, isFile ? null : values[i] + "/**", null, null, null, Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX) + (isFile ? '/' + values[i] : ""), null, instruction.substring(commonPermissions.length()))); //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$
						continue;
					}
				}
			}
		}
		return (FileSet[]) fileSets.toArray(new FileSet[fileSets.size()]);
	}

	//TODO this code andn the generateAntZipTarget() should be refactored using a factory or something like that.
	private void generateAntTarTarget() {
		FileSet[] filesPlugins = new FileSet[plugins.length];
		for (int i = 0; i < plugins.length; i++) {
			Object[] shape = shapeAdvisor.getFinalShape(plugins[i]);
			filesPlugins[i] = new TarFileSet(Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + DEFAULT_PLUGIN_LOCATION + '/' + (String) shape[0], shape[1] == ShapeAdvisor.FILE, null, null, null, null, null, Utils.getPropertyFormat(PROPERTY_PLUGIN_ARCHIVE_PREFIX) + '/' + (String) shape[0], null, null);
		}
		if (plugins.length != 0)
			script.printTarTask(Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH), null, false, true, filesPlugins);

		FileSet[] filesFeatures = new FileSet[features.length];
		for (int i = 0; i < features.length; i++) {
			Object[] shape = shapeAdvisor.getFinalShape(features[i]);
			filesFeatures[i] = new TarFileSet(Utils.getPropertyFormat(PROPERTY_ECLIPSE_BASE) + '/' + DEFAULT_FEATURE_LOCATION + '/' + (String) shape[0], shape[1] == ShapeAdvisor.FILE, null, null, null, null, null, Utils.getPropertyFormat(PROPERTY_FEATURE_ARCHIVE_PREFIX) + '/' + (String) shape[0], null, null);
		}
		if (features.length != 0)
			script.printTarTask(Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH), null, false, true, filesFeatures);

		if (rootFileProviders.size() == 0)
			return;

		FileSet[] permissionSets = generatePermissions(false);
		FileSet[] rootFiles = new FileSet[permissionSets.length + 1];
		System.arraycopy(permissionSets, 0, rootFiles, 1, permissionSets.length);
		rootFiles[0] = new TarFileSet(rootFolder, false, null, "**/**", null, null, null, Utils.getPropertyFormat(PROPERTY_ARCHIVE_PREFIX), null, null); //$NON-NLS-1$
		script.printTarTask(Utils.getPropertyFormat(PROPERTY_ARCHIVE_FULLPATH), null, false, true, rootFiles);
	}

	public void setGenerateJnlp(boolean value) {
		generateJnlp = value;
	}

	public void setSignJars(boolean value) {
		signJars = value;
	}

	public void setProduct(String value) {
		product = value;
	}

	public ProductFile getProductFile() {
		return productFile;
	}

	public void setArchiveFormat(String archiveFormat) {
		this.archiveFormat = archiveFormat;
	}

	public void setGroupConfigs(boolean group) {
		groupConfigs = group;
	}
}
