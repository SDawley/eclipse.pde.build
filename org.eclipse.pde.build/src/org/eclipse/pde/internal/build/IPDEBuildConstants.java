/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.build;

/**
 * Generic constants for this plug-in classes.
 */
public interface IPDEBuildConstants {

	/** PDE Core plug-in id */
	public static final String PI_BOOT = "org.eclipse.core.boot"; //$NON-NLS-1$
	public static final String PI_BOOT_JAR_NAME = "boot.jar"; //$NON-NLS-1$
	public static final String PI_PDEBUILD = "org.eclipse.pde.build"; //$NON-NLS-1$
	public static final String PI_RUNTIME = "org.eclipse.core.runtime"; //$NON-NLS-1$
	public static final String PI_RUNTIME_JAR_NAME = "runtime.jar"; //$NON-NLS-1$

	/** file names */
	public final static String PROPERTIES_FILE = "build.properties"; //$NON-NLS-1$

	// command line arguments
	public static final String ARG_CVS_PASSFILE_LOCATION = "-cvspassfile"; //$NON-NLS-1$
	public static final String ARG_DEV_ENTRIES = "-dev"; //$NON-NLS-1$
	public static final String ARG_DIRECTORY_LOCATION = "-directory"; //$NON-NLS-1$
	public static final String ARG_ELEMENTS = "-elements"; //$NON-NLS-1$
	public static final String ARG_INSTALL_LOCATION = "-install"; //$NON-NLS-1$
	public static final String ARG_NO_CHILDREN = "-nochildren"; //$NON-NLS-1$
	public static final String ARG_PLUGIN_PATH = "-pluginpath"; //$NON-NLS-1$
	public static final String ARG_SCRIPT_NAME = "-scriptname"; //$NON-NLS-1$
	public static final String ARG_SOURCE_LOCATION = "-source"; //$NON-NLS-1$
	
	// default values
	public final static String DEFAULT_BUILD_SCRIPT_FILENAME = "build.xml"; //$NON-NLS-1$
	public final static String DEFAULT_FEATURE_FILENAME_DESCRIPTOR = "feature.xml"; //$NON-NLS-1$
	public final static String DEFAULT_FEATURE_LOCATION = "features"; //$NON-NLS-1$
	public final static String DEFAULT_FETCH_SCRIPT_FILENAME = "fetch.xml"; //$NON-NLS-1$
	public final static String DEFAULT_PLUGIN_LOCATION = "plugins"; //$NON-NLS-1$
	public final static String DEFAULT_TEMPLATE_SCRIPT_FILENAME = "template.xml"; //$NON-NLS-1$

	// status constants	
	public final static int EXCEPTION_FEATURE_MISSING = 1;
	public final static int EXCEPTION_INSTALL_LOCATION_MISSING = 2;
	public final static int EXCEPTION_MALFORMED_URL = 3;
	public final static int EXCEPTION_MODEL_PARSE = 4;
	public final static int EXCEPTION_PLUGIN_MISSING = 5;
	public final static int EXCEPTION_READ_DIRECTORY = 6;
	public final static int EXCEPTION_WRITING_SCRIPT = 7;
	public final static int EXCEPTION_ELEMENT_MISSING = 8;
	public final static int EXCEPTION_ENTRY_MISSING = 9;
	public final static int EXCEPTION_READING_FILE = 10;
	public final static int EXCEPTION_SOURCE_LOCATION_MISSING = 11;
	public final static int EXCEPTION_WRITING_FILE = 12;
	public final static int EXCEPTION_INVALID_JAR_ORDER = 13;
	public final static int EXCEPTION_CLASSPATH_CYCLE = 14;
	public final static int WARNING_MISSING_SOURCE = 20;
	public final static int WARNING_MISSING_MATCHING_PLUGIN = 21;
}
