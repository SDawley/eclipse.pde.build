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

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.pde.internal.build.builder.FeatureBuildScriptGenerator;
import org.eclipse.update.core.Feature;
import org.eclipse.update.core.IIncludedFeatureReference;

public class SourceFeatureWriter extends FeatureWriter {

	public SourceFeatureWriter(OutputStream out, Feature feature, FeatureBuildScriptGenerator generator) throws IOException {
		super(out, feature, generator);
	}

	public void printIncludes() {
		Map parameters = new HashMap();
		// TO CHECK Here we should have the raw list...
		IIncludedFeatureReference[] features = feature.getFeatureIncluded();
		for (int i = 0; i < features.length; i++) {
			parameters.clear();
			try {
				parameters.put("id", features[i].getVersionedIdentifier().getIdentifier()); //$NON-NLS-1$
				parameters.put("version", features[i].getVersionedIdentifier().getVersion()); //$NON-NLS-1$
			} catch (CoreException e) {
				e.printStackTrace(); //TO CHECK better handling of exception
			}

			startTag("includes", parameters); //$NON-NLS-1$
			endTag("includes"); //$NON-NLS-1$
		}
	}
}
