/**********************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved.   This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors: 
 * IBM - Initial API and implementation
 **********************************************************************/
package org.eclipse.pde.internal.build.builder;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import org.eclipse.core.internal.boot.PlatformURLHandler;
import org.eclipse.core.internal.runtime.PlatformURLFragmentConnection;
import org.eclipse.core.internal.runtime.PlatformURLPluginConnection;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.HostSpecification;
import org.eclipse.pde.internal.build.*;
import org.eclipse.pde.internal.build.site.PDEState;

public class ClasspathComputer3_0 implements IClasspathComputer, IPDEBuildConstants, IXMLConstants, IBuildPropertiesConstants {
	private ModelBuildScriptGenerator generator;

	public ClasspathComputer3_0(ModelBuildScriptGenerator modelGenerator) {
		this.generator = modelGenerator;
	}

	/**
	 * Compute the classpath for the given jar.
	 * The path returned conforms to Parent / Self / Prerequisite
	 * 
	 * @param model the plugin containing the jar compiled
	 * @param jar the jar for which the classpath is being compiled
	 * @return String the classpath
	 * @throws CoreException
	 */
	public List getClasspath(BundleDescription model, ModelBuildScriptGenerator.CompiledEntry jar) throws CoreException {
		List classpath = new ArrayList(20);
		List pluginChain = new ArrayList(10);
		String location = generator.getLocation(model);

		//PREREQUISITE
		addPrerequisites(model, classpath, location, pluginChain);

		//SELF
		addSelf(model, jar, classpath, location, pluginChain);

		return classpath;

	}

	/**
	 * Add the specified plugin (including its jars) and its fragments 
	 * @param model
	 * @param classpath
	 * @param location
	 * @throws CoreException
	 */
	private void addPlugin(BundleDescription plugin, List classpath, String location) throws CoreException {
		addRuntimeLibraries(plugin, classpath, location);
		addFragmentsLibraries(plugin, classpath, location);
	}

	/**
	 * Add the runtime libraries for the specified plugin. 
	 * @param model
	 * @param classpath
	 * @param baseLocation
	 * @throws CoreException
	 */
	private void addRuntimeLibraries(BundleDescription model, List classpath, String baseLocation) throws CoreException {
		String[] libraries = getClasspathEntries(model);
		String root = generator.getLocation(model);
		IPath base = Utils.makeRelative(new Path(root), new Path(baseLocation));
		for (int i = 0; i < libraries.length; i++) {
			addDevEntries(model, baseLocation, classpath, Utils.getArrayFromString(generator.getBuildProperties().getProperty(PROPERTY_OUTPUT_PREFIX + libraries[i])));
			String library = base.append(libraries[i]).toString();
			addPathAndCheck(model.getUniqueId(), library, classpath);
		}
	}

	/**
	 * Return the plug-in model object from the plug-in registry for the given
	 * plug-in identifier and version. If the plug-in is not in the registry then
	 * throw an exception.
	 * 
	 * @param id the plug-in identifier
	 * @param version the plug-in version
	 * @return PluginModel
	 * @throws CoreException if the specified plug-in version does not exist in the registry
	 */
	private BundleDescription getPlugin(String id, String version) throws CoreException {
		if (version==null)
			return generator.getSite(false).getRegistry().getResolvedBundle(id);
		else 
			return generator.getSite(false).getRegistry().getResolvedBundle(id, version);
		//TODO Need to put back the fixes for 42444 / 43072 / 48317
		// TODO need to handle optional plugins here.  If an optional is missing it is
		// not an error.  For now return null and essentially ignore missing plugins.
		//	if (plugin == null) {
		//		String pluginName = (version == null) ? id : id + "_" + version; //$NON-NLS-1$
		//		throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_PLUGIN_MISSING, Policy.bind("exception.missingPlugin", pluginName), null)); //$NON-NLS-1$
		//	}
		// TODO Problem with the non-determinism of the value returned by the plugin registry when several plugin exists. Case with a "compile against" set and a "compiled" set that interleaves.		
	}

	/**
	 * Add all fragments of the given plugin
	 * @param plugin
	 * @param classpath
	 * @param baseLocation
	 * @throws CoreException
	 */
	//TODO Check the position of the bin directory seems to be bogus (it appears after the jar)
	private void addFragmentsLibraries(BundleDescription plugin, List classpath, String baseLocation) throws CoreException {
		// if plugin is not a plugin, it's a fragment and there is no fragment for a fragment. So we return.
		BundleDescription[] fragments = plugin.getFragments();
		if (fragments == null)
			return;

		for (int i = 0; i < fragments.length; i++) {
			if (fragments[i] == generator.getModel())
				continue;
			addPluginLibrariesToFragmentLocations(plugin, fragments[i], classpath, baseLocation);
			addRuntimeLibraries(fragments[i], classpath, baseLocation);
		}
	}

	/**
	 * There are cases where the plug-in only declares a library but the real JAR is under
	 * a fragment location. This method gets all the plugin libraries and place them in the
	 * possible fragment location.
	 * 
	 * @param plugin
	 * @param fragment
	 * @param classpath
	 * @param baseLocation
	 * @throws CoreException
	 */
	private void addPluginLibrariesToFragmentLocations(BundleDescription plugin, BundleDescription fragment, List classpath, String baseLocation) throws CoreException {
		//TODO This methods causes the addition of a lot of useless entries. See bug #35544
		//If we reintroduce the test below, we reintroduce the problem 35544	
		//	if (fragment.getRuntime() != null)
		//		return;
		String[] libraries = getClasspathEntries(plugin);

		String root = generator.getLocation(fragment);
		IPath base = Utils.makeRelative(new Path(root), new Path(baseLocation));
		for (int i = 0; i < libraries.length; i++) {
			String libraryName = base.append(libraries[i]).toString();
			addPathAndCheck(fragment.getUniqueId(), libraryName, classpath);
		}
	}

	// Add a path into the classpath for a given model
	// path : The path to add
	// classpath : The classpath in which we want to add this path 
	private void addPathAndCheck(String pluginId, String path, List classpath) {
		path = generator.replaceVariables(path, pluginId == null ? false : generator.getCompiledElements().contains(pluginId));
		if (!classpath.contains(path))
			classpath.add(path);
	}

	private void addSelf(BundleDescription model, ModelBuildScriptGenerator.CompiledEntry jar, List classpath, String location, List pluginChain) throws CoreException {
		// If model is a fragment, we need to add in the classpath the plugin to which it is related
		HostSpecification host = model.getHost();
		if (host != null) {
			addPluginAndPrerequisites(host.getSupplier(), classpath, location, pluginChain);
		}

		// Add the libraries
		Properties modelProperties = generator.getBuildProperties();
		String jarOrder = (String) modelProperties.get(PROPERTY_JAR_ORDER);
		if (jarOrder == null) {
			// if no jar order was specified in build.properties, we add all the libraries but the current one
			// based on the order specified by the plugin.xml. Both library that we compile and .jar provided are processed
			String[] libraries = getClasspathEntries(model);
			if (libraries != null) {
				for (int i = 0; i < libraries.length; i++) {
					String libraryName = libraries[i];
					if (jar.getName(false).equals(libraryName))
						continue;

					boolean isSource = (modelProperties.getProperty(PROPERTY_SOURCE_PREFIX + libraryName) != null);
					if (isSource) {
						addDevEntries(model, location, classpath, Utils.getArrayFromString(modelProperties.getProperty(PROPERTY_OUTPUT_PREFIX + libraryName)));
					}
					//Potential pb: here there maybe a nasty case where the libraries variable may refer to something which is part of the base
					//but $xx$ will replace it by the $xx instead of $basexx. The solution is for the user to use the explicitly set the content
					// of its build.property file
					addPathAndCheck(model.getUniqueId(), libraryName, classpath);
				}
			}
		} else {
			// otherwise we add all the predecessor jars
			String[] order = Utils.getArrayFromString(jarOrder);
			for (int i = 0; i < order.length; i++) {
				if (order[i].equals(jar.getName(false)))
					break;
				addDevEntries(model, location, classpath, Utils.getArrayFromString((String) modelProperties.get(PROPERTY_OUTPUT_PREFIX + order[i])));
				addPathAndCheck(model.getUniqueId(), order[i], classpath);
			}
			// Then we add all the "pure libraries" (the one that does not contain source)
			String[] libraries = getClasspathEntries(model);
			for (int i = 0; i < libraries.length; i++) {
				String libraryName = libraries[i];
				if (modelProperties.get(PROPERTY_SOURCE_PREFIX + libraryName) == null) {
					//Potential pb: if the pure library is something that is being compiled (which is supposetly not the case, but who knows...)
					//the user will get $basexx instead of $ws 
					addPathAndCheck(model.getUniqueId(), libraryName, classpath);
				}
			}
		}

		// add extra classpath if it exists. this code is kept for backward compatibility
		String extraClasspath = (String) modelProperties.get(PROPERTY_JAR_EXTRA_CLASSPATH);
		if (extraClasspath != null) {
			String[] extra = Utils.getArrayFromString(extraClasspath, ";,"); //$NON-NLS-1$

			for (int i = 0; i < extra.length; i++) {
				//Potential pb: if the path refers to something that is being compiled (which is supposetly not the case, but who knows...)
				//the user will get $basexx instead of $ws 
				addPathAndCheck(null, computeExtraPath(extra[i], location), classpath);
			}
		}

		//	add extra classpath if it is specified for the given jar
		String[] jarSpecificExtraClasspath = jar.getExtraClasspath();
		for (int i = 0; i < jarSpecificExtraClasspath.length; i++) {
			//Potential pb: if the path refers to something that is being compiled (which is supposetly not the case, but who knows...)
			//the user will get $basexx instead of $ws 
			addPathAndCheck(null, computeExtraPath(jarSpecificExtraClasspath[i], location), classpath);
		}
	}

	/** 
	 * Convenience method that compute the relative classpath of extra.classpath entries  
	 * @param url a url
	 * @param location location used as a base location to compute the relative path 
	 * @return String the relative path 
	 * @throws CoreException
	 */
	private String computeExtraPath(String url, String location) throws CoreException {
		String relativePath = null;

		String[] urlfragments = Utils.getArrayFromString(url, "/"); //$NON-NLS-1$

		// A valid platform url for a plugin has a leat 3 segments.
		if (urlfragments.length > 2 && urlfragments[0].equals(PlatformURLHandler.PROTOCOL + PlatformURLHandler.PROTOCOL_SEPARATOR)) {
			String modelLocation = null;
			if (urlfragments[1].equalsIgnoreCase(PlatformURLPluginConnection.PLUGIN))
				modelLocation = generator.getLocation(generator.getSite(false).getRegistry().getResolvedBundle(urlfragments[2]));

			if (urlfragments[1].equalsIgnoreCase(PlatformURLFragmentConnection.FRAGMENT))
				modelLocation = generator.getLocation(generator.getSite(false).getRegistry().getResolvedBundle(urlfragments[2]));

			if (urlfragments[1].equalsIgnoreCase("resource")) { //$NON-NLS-1$
				String message = Policy.bind("exception.url", generator.getPropertiesFileName() + "::" + url); //$NON-NLS-1$  //$NON-NLS-2$
				throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_MALFORMED_URL, message, null));
			}
			if (modelLocation != null) {
				for (int i = 3; i < urlfragments.length; i++) {
					if (i == 3)
						modelLocation += urlfragments[i];
					else
						modelLocation += '/' + urlfragments[i]; //$NON-NLS-1$
				}
				return relativePath = Utils.makeRelative(new Path(modelLocation), new Path(location)).toOSString();
			}
		}

		// Then it's just a regular URL, or just something that will be added at the end of the classpath for backward compatibility.......
		try {
			URL extraURL = new URL(url);
			try {
				relativePath = Utils.makeRelative(new Path(Platform.resolve(extraURL).getFile()), new Path(location)).toOSString();
			} catch (IOException e) {
				String message = Policy.bind("exception.url", generator.getPropertiesFileName() + "::" + url); //$NON-NLS-1$  //$NON-NLS-2$
				throw new CoreException(new Status(IStatus.ERROR, PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_MALFORMED_URL, message, e));
			}
		} catch (MalformedURLException e) {
			relativePath = url;
			//TODO remove this backward compatibility support for as soon as we go to 2.2 and put back the exception
			//		String message = Policy.bind("exception.url", PROPERTIES_FILE + "::"+url); //$NON-NLS-1$  //$NON-NLS-2$
			//		throw new CoreException(new Status(IStatus.ERROR,PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_MALFORMED_URL, message,e));
		}
		return relativePath;
	}

	//Add the prerequisite of a given plugin (target)
	private void addPrerequisites(BundleDescription target, List classpath, String baseLocation, List pluginChain) throws CoreException {
		if (pluginChain.contains(target)) {		
			String message = Policy.bind("error.pluginCycle"); //$NON-NLS-1$
			throw new CoreException(new Status(IStatus.ERROR, IPDEBuildConstants.PI_PDEBUILD, IPDEBuildConstants.EXCEPTION_CLASSPATH_CYCLE, message, null));
		}

		// add libraries from pre-requisite plug-ins.  Don't worry about the export flag
		// as all required plugins may be required for compilation.
		BundleDescription[] requires = PDEState.getDependentBundles(target);
		pluginChain.add(target);
		for (int i = 0; i < requires.length; i++) {
			addPluginAndPrerequisites(requires[i], classpath, baseLocation, pluginChain);
		}
		pluginChain.remove(target);
	}

	/**
	 * The pluginChain parameter is used to keep track of possible cycles. If prerequisite is already
	 * present in the chain it is not included in the classpath.
	 * 
	 * @param target : the plugin for which we are going to introduce
	 * @param classpath 
	 * @param baseLocation
	 * @param pluginChain
	 * @param considerExport
	 * @throws CoreException
	 */
	private void addPluginAndPrerequisites(BundleDescription target, List classpath, String baseLocation, List pluginChain) throws CoreException {
		addPlugin(target, classpath, baseLocation);
		addPrerequisites(target, classpath, baseLocation, pluginChain);
	}

	/**
	 * 
	 * @param model
	 * @param baseLocation
	 * @param classpath
	 * @throws CoreException
	 */
	private void addDevEntries(BundleDescription model, String baseLocation, List classpath, String[] jarSpecificEntries) throws CoreException {
		// first we verify if the addition of dev entries is required
		if (generator.devEntries != null && generator.devEntries.length == 0)
			return;

		if (generator.devEntries == null && (jarSpecificEntries == null || jarSpecificEntries.length == 0))
			return;

		String[] entries;
		// if jarSpecificEntries is given, then it overrides devEntries 
		if (jarSpecificEntries != null && jarSpecificEntries.length > 0)
			entries = jarSpecificEntries;
		else
			entries = generator.devEntries;

		IPath root = Utils.makeRelative(new Path(generator.getLocation(model)), new Path(baseLocation));
		String path;
		for (int i = 0; i < entries.length; i++) {
			path = root.append(entries[i]).toString();
			addPathAndCheck(model.getUniqueId(), path, classpath);
		}
	}
	
	//Return the jar name from the classpath 
	private String[] getClasspathEntries(BundleDescription bundle) throws CoreException {
		return generator.getClasspathEntries(bundle);
	}
}
