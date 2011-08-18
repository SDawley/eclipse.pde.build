/*******************************************************************************
 *  Copyright (c) 2000, 2009 IBM Corporation and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Contributors:
 *     IBM - Initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.build;

import java.io.*;
import java.net.URI;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.State;
import org.eclipse.osgi.util.NLS;
import org.eclipse.pde.internal.build.ant.AntScript;
import org.eclipse.pde.internal.build.builder.BuildDirector;
import org.eclipse.pde.internal.build.site.*;
import org.eclipse.pde.internal.build.site.compatibility.FeatureEntry;
import org.eclipse.pde.internal.build.site.compatibility.SiteManager;
import org.osgi.framework.Version;

/**
 * Generic super-class for all script generator classes. 
 * It contains basic informations like the script, the configurations, and a location 
 */
public abstract class AbstractScriptGenerator implements IXMLConstants, IPDEBuildConstants, IBuildPropertiesConstants {
	private static Properties immutableAntProperties = null;
	protected static boolean embeddedSource = false;
	protected static boolean forceUpdateJarFormat = false;
	private static List configInfos;
	protected static String workingDirectory;
	protected static boolean buildingOSGi = true;
	protected URI[] contextMetadata = null;
	protected AntScript script;
	protected Properties platformProperties;
	protected String productQualifier;

	private static PDEUIStateWrapper pdeUIState;

	/** Location of the plug-ins and fragments. */
	protected String[] sitePaths;
	protected String[] pluginPath;
	protected BuildTimeSiteFactory siteFactory;

	/**
	 * Indicate whether the content of the pdestate should only contain the plugins that are in the transitive closure of the features being built
	 */
	protected boolean filterState = false;
	protected List featuresForFilterRoots = new ArrayList();
	protected List pluginsForFilterRoots = new ArrayList();
	protected boolean filterP2Base = false;

	protected boolean reportResolutionErrors;

	static {
		// By default, a generic configuration is set
		configInfos = new ArrayList(1);
		configInfos.add(Config.genericConfig());
	}

	public static List getConfigInfos() {
		return configInfos;
	}

	/**
	 * Starting point for script generation. See subclass implementations for
	 * individual comments.
	 * 
	 * @throws CoreException
	 */
	public abstract void generate() throws CoreException;

	protected static void setStaticAntProperties(Properties properties) {
		if (properties == null) {
			immutableAntProperties = new Properties();
			BuildDirector.p2Gathering = false;
		} else
			immutableAntProperties = properties;
		if (getImmutableAntProperty(IBuildPropertiesConstants.PROPERTY_PACKAGER_MODE) == null) {
			immutableAntProperties.setProperty(IBuildPropertiesConstants.PROPERTY_PACKAGER_MODE, "false"); //$NON-NLS-1$
		}
		//When we are generating build scripts, the normalization needs to be set, and when doing packaging the default is to set normalization to true for backward compatibility 
		if (!getPropertyAsBoolean(IBuildPropertiesConstants.PROPERTY_PACKAGER_MODE) || getImmutableAntProperty(IBuildPropertiesConstants.PROPERTY_PACKAGER_AS_NORMALIZER) == null) {
			immutableAntProperties.setProperty(IBuildPropertiesConstants.PROPERTY_PACKAGER_AS_NORMALIZER, "true"); //$NON-NLS-1$
		}

		if (getPropertyAsBoolean(IBuildPropertiesConstants.PROPERTY_P2_GATHERING))
			BuildDirector.p2Gathering = true;
	}

	public static String getImmutableAntProperty(String key) {
		return getImmutableAntProperty(key, null);
	}

	public static boolean getPropertyAsBoolean(String key) {
		String booleanValue = getImmutableAntProperty(key, null);
		if ("true".equalsIgnoreCase(booleanValue)) //$NON-NLS-1$
			return true;
		return false;
	}

	public static String getImmutableAntProperty(String key, String defaultValue) {
		if (immutableAntProperties == null || !immutableAntProperties.containsKey(key))
			return defaultValue;
		Object obj = immutableAntProperties.get(key);
		return (obj instanceof String) ? (String) obj : null;
	}

	public static void setConfigInfo(String spec) throws CoreException {
		configInfos.clear();
		String[] configs = Utils.getArrayFromStringWithBlank(spec, "&"); //$NON-NLS-1$
		configInfos = new ArrayList(configs.length);
		String[] os = new String[configs.length];
		String[] ws = new String[configs.length];
		String[] archs = new String[configs.length];
		for (int i = 0; i < configs.length; i++) {
			String[] configElements = Utils.getArrayFromStringWithBlank(configs[i], ","); //$NON-NLS-1$
			if (configElements.length != 3) {
				IStatus error = new Status(IStatus.ERROR, IPDEBuildConstants.PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_CONFIG_FORMAT, NLS.bind(Messages.error_configWrongFormat, configs[i]), null);
				throw new CoreException(error);
			}
			Config aConfig = new Config(configs[i]);
			if (aConfig.equals(Config.genericConfig()))
				configInfos.add(Config.genericConfig());
			else
				configInfos.add(aConfig);

			// create a list of all ws, os and arch to feed the SiteManager
			os[i] = aConfig.getOs();
			ws[i] = aConfig.getWs();
			archs[i] = aConfig.getArch();
		}
		SiteManager.setOS(Utils.getStringFromArray(os, ",")); //$NON-NLS-1$
		SiteManager.setWS(Utils.getStringFromArray(ws, ",")); //$NON-NLS-1$
		SiteManager.setArch(Utils.getStringFromArray(archs, ",")); //$NON-NLS-1$
	}

	public void setWorkingDirectory(String location) {
		workingDirectory = location;
	}

	/**
	 * Return the file system location for the given plug-in model object.
	 * 
	 * @param model the plug-in
	 * @return String
	 */
	public String getLocation(BundleDescription model) {
		return model.getLocation();
	}

	static public class MissingProperties extends Properties {
		private static final long serialVersionUID = 3546924667060303927L;
		private static MissingProperties singleton;

		private MissingProperties() {
			//nothing to do;
		}

		public synchronized Object setProperty(String key, String value) {
			throw new UnsupportedOperationException();
		}

		public synchronized Object put(Object key, Object value) {
			throw new UnsupportedOperationException();
		}

		public static MissingProperties getInstance() {
			if (singleton == null)
				singleton = new MissingProperties();
			return singleton;
		}
	}

	public static Properties readProperties(String location, String fileName, int errorLevel) throws CoreException {
		Properties result = new Properties();
		File file = new File(location, fileName);
		try {
			InputStream input = new BufferedInputStream(new FileInputStream(file));
			try {
				result.load(input);
			} finally {
				input.close();
			}
		} catch (FileNotFoundException e) {
			if (errorLevel != IStatus.INFO && errorLevel != IStatus.OK) {
				String message = NLS.bind(Messages.exception_missingFile, file);
				BundleHelper.getDefault().getLog().log(new Status(errorLevel, PI_PDEBUILD, EXCEPTION_READING_FILE, message, null));
			}
			result = MissingProperties.getInstance();
		} catch (IOException e) {
			String message = NLS.bind(Messages.exception_readingFile, file);
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_READING_FILE, message, e));
		}
		return result;
	}

	public void openScript(String scriptLocation, String scriptName) throws CoreException {
		if (script != null)
			return;
		script = newAntScript(scriptLocation, scriptName);
	}

	protected static AntScript newAntScript(String scriptLocation, String scriptName) throws CoreException {
		AntScript result = null;
		try {
			OutputStream scriptStream = new BufferedOutputStream(new FileOutputStream(scriptLocation + '/' + scriptName));
			try {
				result = new AntScript(scriptStream);
			} catch (IOException e) {
				try {
					scriptStream.close();
					String message = NLS.bind(Messages.exception_writingFile, scriptLocation + '/' + scriptName);
					throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
				} catch (IOException e1) {
					// Ignored		
				}
			}
		} catch (FileNotFoundException e) {
			String message = NLS.bind(Messages.exception_writingFile, scriptLocation + '/' + scriptName);
			throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, EXCEPTION_WRITING_FILE, message, e));
		}
		return result;
	}

	public void closeScript() {
		script.close();
	}

	public void setBuildingOSGi(boolean b) {
		buildingOSGi = b;
	}

	public static boolean isBuildingOSGi() {
		return buildingOSGi;
	}

	public static String getWorkingDirectory() {
		return workingDirectory;
	}

	public static String getDefaultOutputFormat() {
		return "zip"; //$NON-NLS-1$
	}

	public static boolean getDefaultEmbeddedSource() {
		return false;
	}

	public static void setEmbeddedSource(boolean embed) {
		embeddedSource = embed;
	}

	public static boolean getForceUpdateJarFormat() {
		return false;
	}

	public static void setForceUpdateJar(boolean force) {
		forceUpdateJarFormat = force;
	}

	public static String getDefaultConfigInfos() {
		return "*, *, *"; //$NON-NLS-1$
	}

	public static boolean getDefaultBuildingOSGi() {
		return true;
	}

	protected static boolean loadP2Class() {
		try {
			BundleHelper.getDefault().getClass().getClassLoader().loadClass("org.eclipse.equinox.internal.provisional.p2.metadata.generator.Generator"); //$NON-NLS-1$
			return true;
		} catch (Throwable e) {
			return false;
		}
	}

	/**
	 * Return a build time site referencing things to be built.   
	 * @param refresh : indicate if a refresh must be performed. Although this flag is set to true, a new site is not rebuild if the urls of the site did not changed 
	 * @return BuildTimeSite
	 * @throws CoreException
	 */
	public BuildTimeSite getSite(boolean refresh) throws CoreException {
		if (siteFactory != null && refresh == false)
			return siteFactory.createSite();

		if (siteFactory == null || refresh == true) {
			siteFactory = new BuildTimeSiteFactory();
			siteFactory.setFilterState(filterState);
			siteFactory.setFilterRoots(featuresForFilterRoots, pluginsForFilterRoots);
			siteFactory.setReportResolutionErrors(reportResolutionErrors);
			siteFactory.setFilterP2Base(filterP2Base);
		}

		siteFactory.setSitePaths(getPaths());
		siteFactory.setEESources(getEESources());
		siteFactory.setInitialState(pdeUIState);
		BuildTimeSite result = siteFactory.createSite();
		if (platformProperties != null)
			result.setPlatformPropeties(platformProperties);
		return result;
	}

	/**
	 * Method getPaths.  These are the paths used for the BuildTimeSite
	 * @return URL[]
	 */
	private String[] getPaths() {
		if (sitePaths == null) {
			if (pluginPath != null) {
				sitePaths = new String[pluginPath.length + 1];
				System.arraycopy(pluginPath, 0, sitePaths, 0, pluginPath.length);
				sitePaths[sitePaths.length - 1] = workingDirectory;
			} else {
				sitePaths = new String[] {workingDirectory};
			}
		}

		return sitePaths;
	}

	protected String[] getEESources() {
		return null;
	}

	public void setBuildSiteFactory(BuildTimeSiteFactory siteFactory) {
		this.siteFactory = siteFactory;
	}

	/**
	 * Return the path of the plugins		//TODO Do we need to add support for features, or do we simply consider one list of URL? It is just a matter of style/
	 * @return URL[]
	 */
	public String[] getPluginPath() {
		return pluginPath;
	}

	/**
	 * Sets the pluginPath.
	 * 
	 * @param path
	 */
	public void setPluginPath(String[] path) {
		pluginPath = path;
	}

	public void setPDEState(State state) {
		ensurePDEUIStateNotNull();
		pdeUIState.setState(state);
	}

	public void setStateExtraData(HashMap classpath, Map patchData) {
		setStateExtraData(classpath, patchData, null);
	}

	public void setStateExtraData(HashMap classpath, Map patchData, Map outputFolders) {
		ensurePDEUIStateNotNull();
		pdeUIState.setExtraData(classpath, patchData, outputFolders);
	}

	public void setNextId(long nextId) {
		ensurePDEUIStateNotNull();
		pdeUIState.setNextId(nextId);
	}

	protected void flushState() {
		pdeUIState = null;
	}

	private void ensurePDEUIStateNotNull() {
		if (pdeUIState == null)
			pdeUIState = new PDEUIStateWrapper();
	}

	protected boolean havePDEUIState() {
		return pdeUIState != null;
	}

	public ProductFile loadProduct(String product) throws CoreException {
		//the ProductFile uses the OS to determine which icons to return, we don't care so can use null
		//this is better since this generator may be used for multiple OS's
		return loadProduct(product, null);
	}

	public ProductFile loadProduct(String product, String os) throws CoreException {
		if (product == null || product.startsWith("${")) { //$NON-NLS-1$
			return null;
		}
		String productPath = findFile(product, false);
		File f = null;
		if (productPath != null) {
			f = new File(productPath);
		} else {
			// couldn't find productFile, try it as a path directly
			f = new File(product);
			if (!f.exists() || !f.isFile()) {
				// doesn't exist, try it as a path relative to the working directory
				f = new File(getWorkingDirectory(), product);
				if (!f.exists() || !f.isFile()) {
					f = new File(getWorkingDirectory() + "/" + DEFAULT_PLUGIN_LOCATION, product); //$NON-NLS-1$
				}
			}
		}
		return new ProductFile(f.getAbsolutePath(), os);
	}

	//Find a file in a bundle or a feature.
	//location is assumed to be structured like : /<featureId | pluginId>/path.to.the.file
	protected String findFile(String location, boolean makeRelative) {
		if (location == null || location.length() == 0)
			return null;

		//shortcut building the site if we don't need to
		if (new File(location).exists())
			return location;

		PDEState state;
		try {
			state = getSite(false).getRegistry();
		} catch (CoreException e) {
			return null;
		}
		Path path = new Path(location);
		String id = path.segment(0);
		BundleDescription[] matches = state.getState().getBundles(id);
		if (matches != null && matches.length != 0) {
			BundleDescription bundle = matches[0];
			if (bundle != null) {
				String result = checkFile(new Path(bundle.getLocation()), path, makeRelative);
				if (result != null)
					return result;
			}
		}
		// Couldn't find the file in any of the plugins, try in a feature.
		BuildTimeFeature feature = null;
		try {
			feature = getSite(false).findFeature(id, null, false);
		} catch (CoreException e) {
			//Ignore
		}
		if (feature == null)
			return null;

		String featureRoot = feature.getRootLocation();
		if (featureRoot != null)
			return checkFile(new Path(featureRoot), path, makeRelative);
		return null;
	}

	protected String findConfigFile(ProductFile productFile, String os) {
		String path = productFile.getConfigIniPath(os);
		if (path == null)
			return null;

		String result = findFile(path, false);
		if (result != null)
			return result;

		// couldn't find productFile, try it as a path directly
		File f = new File(path);
		if (f.exists() && f.isFile())
			return f.getAbsolutePath();

		// relative to the working directory
		f = new File(getWorkingDirectory(), path);
		if (f.exists() && f.isFile())
			return f.getAbsolutePath();

		// relative to the working directory/plugins
		f = new File(getWorkingDirectory() + "/" + DEFAULT_PLUGIN_LOCATION, path); //$NON-NLS-1$
		if (f.exists() && f.isFile())
			return f.getAbsolutePath();

		//relative to .product file
		f = new File(new File(productFile.getLocation()).getParent(), path);
		if (f.exists() && f.isFile())
			return f.getAbsolutePath();

		return null;
	}

	private String checkFile(IPath base, Path target, boolean makeRelative) {
		IPath path = base.append(target.removeFirstSegments(1));
		String result = path.toOSString();
		if (!new File(result).exists())
			return null;
		if (makeRelative)
			return Utils.makeRelative(path, new Path(workingDirectory)).toOSString();
		return result;
	}

	public void setFilterState(boolean filter) {
		filterState = filter;
	}

	public void setFilterP2Base(boolean filter) {
		filterP2Base = filter;
	}

	public void setContextMetadataRepositories(URI[] uris) {
		this.contextMetadata = uris;
	}

	public URI[] getContextMetadata() {
		return contextMetadata;
	}

	public void setProductQualifier(String value) {
		productQualifier = value;
	}

	/*
	 * If the user has specified a platform properties then load it.
	 */
	public void setPlatformProperties(String filename) {
		if (filename == null || filename.trim().length() == 0)
			return;
		File file = new File(filename);
		if (!file.exists())
			return;
		platformProperties = new Properties();
		InputStream input = null;
		try {
			input = new BufferedInputStream(new FileInputStream(file));
			platformProperties.load(input);
		} catch (IOException e) {
			platformProperties = null;
			String message = NLS.bind(Messages.error_loading_platform_properties, filename);
			IStatus status = new Status(IStatus.WARNING, IPDEBuildConstants.PI_PDEBUILD, message, e);
			BundleHelper.getDefault().getLog().log(status);
		} finally {
			if (input != null)
				try {
					input.close();
				} catch (IOException e) {
					// ignore
				}
		}
	}

	protected void generateProductReplaceTask(ProductFile product, String productFilePath) {
		if (product == null)
			return;

		BuildTimeSite site = null;
		try {
			site = getSite(false);
		} catch (CoreException e1) {
			return;
		}

		String version = product.getVersion();
		if (version.endsWith(PROPERTY_QUALIFIER)) {
			Version oldVersion = new Version(version);
			version = oldVersion.getMajor() + "." + oldVersion.getMinor() + "." + oldVersion.getMicro() + "." + Utils.getPropertyFormat(PROPERTY_P2_PRODUCT_QUALIFIER); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

		List productEntries = product.getProductEntries();
		String mappings = Utils.getEntryVersionMappings((FeatureEntry[]) productEntries.toArray(new FeatureEntry[productEntries.size()]), site);

		script.println("<eclipse.idReplacer productFilePath=\"" + AntScript.getEscaped(productFilePath) + "\""); //$NON-NLS-1$ //$NON-NLS-2$
		script.println("                    selfVersion=\"" + version + "\" "); //$NON-NLS-1$ //$NON-NLS-2$
		if (product.useFeatures())
			script.println("                    featureIds=\"" + mappings + "\"/>"); //$NON-NLS-1$ //$NON-NLS-2$
		else
			script.println("                    pluginIds=\"" + mappings + "\"/>"); //$NON-NLS-1$ //$NON-NLS-2$ 

		return;
	}
}
