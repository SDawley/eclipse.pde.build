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
package org.eclipse.pde.internal.build.site;

import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.VersionConstraint;
import org.eclipse.pde.internal.build.*;
import org.eclipse.update.core.*;

/**
 * This site represent a site at build time. A build time site is made of code
 * to compile, and a potential installation of eclipse (or derived products)
 * against which the code must be compiled.
 * Moreover this site provide access to a pluginRegistry.
 */
public class BuildTimeSite extends Site implements ISite, IPDEBuildConstants, IXMLConstants {
	private PDEState state;
	private boolean compile21 = false;// ! AbstractScriptGenerator.isBuildingOSGi();
	
	public PDEState getRegistry() throws CoreException {
		if (state == null) {
			// create the registry according to the site where the code to compile is, and a existing installation of eclipse 
			BuildTimeSiteContentProvider contentProvider = (BuildTimeSiteContentProvider) getSiteContentProvider();
			
			if(compile21)
				state = new PluginRegistryConverter();
			else
				state = new PDEState();
			state.addBundles(contentProvider.getPluginPaths());

			state.resolveState();
			BundleDescription[] allBundles = state.getState().getBundles();
			BundleDescription[] resolvedBundles = state.getState().getResolvedBundles();
			if (allBundles.length == resolvedBundles.length)
				return state;
			
			//display a report of the unresolved constraints
			for (int i = 0; i < allBundles.length; i++) {
				BundleHelper.getDefault().getLog();
				if (! allBundles[i].isResolved()) {
					String message = "Bundle: " + allBundles[i].getUniqueId() + '\n'; //$NON-NLS-1$
					VersionConstraint[] unsatisfiedConstraint = allBundles[i].getUnsatisfiedConstraints();
					for (int j = 0; j < unsatisfiedConstraint.length; j++) {
						message += '\t' + unsatisfiedConstraint[j].toString() + '\n';
					}
					IStatus status = new Status(IStatus.WARNING, IPDEBuildConstants.PI_PDEBUILD,  IPDEBuildConstants.EXCEPTION_STATE_PROBLEM, Policy.bind("exception.registryResolution", message), null);//$NON-NLS-1$
					BundleHelper.getDefault().getLog().log(status);	
				}
			}
		}
		return state;
	}

	public IFeature findFeature(String featureId) throws CoreException {
		ISiteFeatureReference[] features = getFeatureReferences();
		for (int i = 0; i < features.length; i++) {
			if (features[i].getVersionedIdentifier().getIdentifier().equals(featureId))
				return features[i].getFeature(null);
		}
		return null;
	}

}
