/*******************************************************************************
 * Copyright (c) 2007, 2008 IBM Corporation and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: IBM Corporation - initial API and implementation
 ******************************************************************************/

package org.eclipse.pde.build.internal.tests;

import java.io.*;
import java.net.URL;
import java.util.*;

import org.apache.tools.ant.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.osgi.service.resolver.*;
import org.eclipse.pde.build.internal.tests.ant.AntUtils;
import org.eclipse.pde.build.internal.tests.ant.TestBrandTask;
import org.eclipse.pde.build.tests.*;
import org.eclipse.pde.internal.build.*;
import org.eclipse.pde.internal.build.builder.BuildDirector;
import org.eclipse.pde.internal.swt.tools.IconExe;
import org.osgi.framework.Version;

public class ProductTests extends PDETestCase {

	public void testBug192127() throws Exception {
		IFolder buildFolder = newTest("192127");
		IFolder containerFeature = Utils.createFolder(buildFolder, "features/org.eclipse.pde.build.container.feature");

		File delta = Utils.findDeltaPack();
		assertNotNull(delta);

		// Exporting from the UI gives the container feature some /Eclipse.App root files
		Utils.generateFeature(buildFolder, "org.eclipse.pde.build.container.feature", null, null, "/rcp/rcp.product", true, true);
		Properties featureProperties = new Properties();
		featureProperties.put("root", "/temp/");
		Utils.storeBuildProperties(containerFeature, featureProperties);

		Properties properties = BuildConfiguration.getBuilderProperties(buildFolder);
		properties.put("product", "/rcp/rcp.product");
		properties.put("configs", "macosx,carbon,x86");
		if (!delta.equals(new File((String) properties.get("baseLocation"))))
			properties.put("pluginPath", delta.getAbsolutePath());
		URL resource = FileLocator.find(Platform.getBundle("org.eclipse.pde.build"), new Path("/scripts/productBuild/allElements.xml"), null);
		properties.put("allElementsFile", FileLocator.toFileURL(resource).getPath());
		Utils.storeBuildProperties(buildFolder, properties);

		runBuild(buildFolder);

		Set entries = new HashSet();
		entries.add("eclipse/.eclipseproduct");
		entries.add("eclipse/configuration/config.ini");
		entries.add("eclipse/rcp.app/Contents/Info.plist");
		entries.add("eclipse/rcp.app/Contents/MacOS/rcp");
		entries.add("eclipse/rcp.app/Contents/MacOS/rcp.ini");

		entries.add("eclipse/Eclipse.app/");

		//bug 206788 names the archive .zip
		assertZipContents(buildFolder, "I.TestBuild/eclipse-macosx.carbon.x86.zip", entries, false);
		assertTrue(entries.contains("eclipse/Eclipse.app/"));
		assertTrue(entries.size() == 1);
	}

	public void test218878() throws Exception {
		//platform specific config.ini files
		//files copied from resources folder
		IFolder buildFolder = newTest("218878");

		File delta = Utils.findDeltaPack();
		assertNotNull(delta);
		
		Properties properties = BuildConfiguration.getBuilderProperties(buildFolder);
		properties.put("product", "acme.product");
		properties.put("configs", "win32,win32,x86 & linux, gtk, x86");
		if (!delta.equals(new File((String) properties.get("baseLocation"))))
			properties.put("pluginPath", delta.getAbsolutePath());
		Utils.storeBuildProperties(buildFolder, properties);

		runProductBuild(buildFolder);

		Set entries = new HashSet();
		entries.add("eclipse/pablo.exe");
		entries.add("eclipse/configuration/config.ini");

		assertZipContents(buildFolder, "I.TestBuild/eclipse-win32.win32.x86.zip", entries, false);

		IFile win32Config = buildFolder.getFile("win32.config.ini");
		Utils.extractFromZip(buildFolder, "I.TestBuild/eclipse-win32.win32.x86.zip", "eclipse/configuration/config.ini", win32Config);
		Properties props = Utils.loadProperties(win32Config);
		assertEquals("win32", props.getProperty("os"));

		IFile linuxConfig = buildFolder.getFile("linux.config.ini");
		Utils.extractFromZip(buildFolder, "I.TestBuild/eclipse-linux.gtk.x86.zip", "eclipse/configuration/config.ini", linuxConfig);
		props = Utils.loadProperties(linuxConfig);
		assertEquals("linux", props.getProperty("os"));
	}
	
	public void test234032() throws Exception {
		IFolder buildFolder = newTest("234032");
		
		File delta = Utils.findDeltaPack();
		assertNotNull(delta);
		
		Properties properties = BuildConfiguration.getBuilderProperties(buildFolder);
		properties.put("product", "test.product");
		properties.put("configs", "macosx,carbon,ppc");
		properties.put("archivesFormat", "macosx,carbon,ppc-folder");
		if (!delta.equals(new File((String) properties.get("baseLocation"))))
			properties.put("pluginPath", delta.getAbsolutePath());
		Utils.storeBuildProperties(buildFolder, properties);
		
		runProductBuild(buildFolder);
		
		IFile iniFile = buildFolder.getFile("tmp/eclipse/test.app/Contents/MacOS/test.ini");
		assertLogContainsLine(iniFile, "-Dfoo=bar");
	}
	
	public void test237922() throws Exception {
		IFolder buildFolder = newTest("237922");
		
		File delta = Utils.findDeltaPack();
		assertNotNull(delta);
		
		Utils.generateFeature(buildFolder, "F", null, new String[] {"rcp"});
		
		Properties properties = BuildConfiguration.getScriptGenerationProperties(buildFolder, "feature", "F");
		properties.put("product", "/rcp/rcp.product");
		properties.put("configs", "win32,win32,x86");
		
		generateScripts(buildFolder, properties);
		
			
		IFile assembleScript = buildFolder.getFile("assemble.F.win32.win32.x86.xml");
		
		Map alternateTasks = new HashMap();
		alternateTasks.put("eclipse.brand", "org.eclipse.pde.build.internal.tests.ant.TestBrandTask");
		Project antProject = assertValidAntScript(assembleScript, alternateTasks);
		Target main = (Target) antProject.getTargets().get("main");
		assertNotNull(main);
		TestBrandTask brand = (TestBrandTask) AntUtils.getFirstChildByName(main, "eclipse.brand");
		assertNotNull(brand);
		
		assertTrue(brand.icons.indexOf("mail.ico") > 0);
	}
	
	public void test237747() throws Exception {
		IFolder buildFolder = newTest("237747");

		File delta = Utils.findDeltaPack();
		assertNotNull(delta);
		
		IFolder fooFolder = Utils.createFolder(buildFolder, "plugins/foo");
		Utils.generateBundle(fooFolder, "foo");

		StringBuffer buffer = new StringBuffer();
		buffer.append("<product name=\"Foo\" id=\"foo.product\" application=\"org.eclipse.ant.core.antRunner\" useFeatures=\"false\">");
		buffer.append("  <configIni use=\"default\"/>");
		buffer.append("  <plugins>");
		buffer.append("    <plugin id=\"org.eclipse.osgi\"/>");
		buffer.append("  </plugins>");
		buffer.append("</product> ");
		Utils.writeBuffer(buildFolder.getFile("plugins/foo/foo.product"), buffer);

		Properties properties = BuildConfiguration.getBuilderProperties(buildFolder);
		properties.put("product", "/foo/foo.product");
		properties.put("configs", "win32,win32,x86_64 & hpux, motif, ia64_32");
		if (!delta.equals(new File((String) properties.get("baseLocation"))))
			properties.put("pluginPath", delta.getAbsolutePath());
		Utils.storeBuildProperties(buildFolder, properties);
		
		runProductBuild(buildFolder);
		
		assertResourceFile(buildFolder, "I.TestBuild/eclipse-hpux.motif.ia64_32.zip");
		assertResourceFile(buildFolder, "I.TestBuild/eclipse-win32.win32.x86_64.zip");
	}

	public void testBug238001() throws Exception {
		IFolder buildFolder = newTest("238001");
		
		File delta = Utils.findDeltaPack();
		assertNotNull(delta);

		FilenameFilter filter = new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.startsWith("org.eclipse.equinox.executable");
			}
		};
		File[] files = new File(delta, "features").listFiles(filter);

		File win32Exe = new File(files[0], "bin/win32/win32/x86/launcher.exe");
		assertTrue(win32Exe.exists());
		File win64Exe = new File(files[0], "bin/win32/win32/x86_64/launcher.exe");
		assertTrue(win64Exe.exists());

		IFile win32File = buildFolder.getFile("win32.exe");
		win32File.create(new BufferedInputStream(new FileInputStream(win32Exe)), IResource.FORCE, null);
		IFile win64File = buildFolder.getFile("win64.exe");
		win64File.create(new BufferedInputStream(new FileInputStream(win64Exe)), IResource.FORCE, null);

		//steal the icons from test 237922
		URL ico = FileLocator.find(Platform.getBundle(Activator.PLUGIN_ID), new Path("/resources/237922/rcp/icons/mail.ico"), null);
		IFile icoFile = buildFolder.getFile("mail.ico");
		icoFile.create(ico.openStream(), IResource.FORCE, null);
		
		//IconExe prints to stderr, redirect that to a file
		PrintStream oldErr = System.err;
		PrintStream newErr = new PrintStream(new FileOutputStream(buildFolder.getLocation().toOSString() + "/out.out"));
		System.setErr(newErr);
		IconExe.main(new String[] {win32File.getLocation().toOSString(), icoFile.getLocation().toOSString()});
		IconExe.main(new String[] {win64File.getLocation().toOSString(), icoFile.getLocation().toOSString()});
		System.setErr(oldErr);
		newErr.close();

		assertEquals(new File(buildFolder.getLocation().toOSString(), "out.out").length(), 0);
	}
	
	public void testBug249410() throws Exception {
		IFolder buildFolder = newTest("249410");
		IFile product = buildFolder.getFile("foo.product");
		Utils.generateFeature(buildFolder, "f", null, new String[] {"a", "b", "c", "d"});
		Utils.generateProduct(product, "foo.product", "1.0.0", new String[] {"f"}, true);

		AssembleScriptGenerator.setConfigInfo("win32,win32,x86 & linux,gtk,x86");
		Config win32 = new Config("win32,win32,x86");
		Config linux = new Config("linux, gtk, x86");
		AssemblyInformation assembly = new AssemblyInformation();
		StateObjectFactory factory = Platform.getPlatformAdmin().getFactory();

		BundleDescription a = factory.createBundleDescription(1, "a", Version.emptyVersion, null, null, null, null, null, true, true, true, null, null, null, null);
		BundleDescription b = factory.createBundleDescription(2, "b", Version.emptyVersion, null, null, null, null, null, true, true, true, null, null, null, null);
		assembly.addPlugin(win32, a);
		assembly.addPlugin(linux, a);
		assembly.addPlugin(win32, b);
		assembly.addPlugin(linux, b);
		assembly.addPlugin(linux, factory.createBundleDescription(3, "c", Version.emptyVersion, null, null, null, null, null, true, true, true, "(& (osgi.ws=gtk) (osgi.os=linux) (osgi.arch=x86))", null, null, null));
		assembly.addPlugin(win32, factory.createBundleDescription(4, "d", Version.emptyVersion, null, null, null, null, null, true, true, true, "(& (osgi.ws=win32) (osgi.os=win32) (osgi.arch=x86))", null, null, null));

		BuildDirector director = new BuildDirector(assembly);
		ProductGenerator generator = new ProductGenerator();
		generator.setDirector(director);
		generator.setWorkingDirectory(buildFolder.getLocation().toOSString());
		generator.setRoot(buildFolder.getLocation().toOSString() + "/");
		generator.setProduct(product.getLocation().toOSString());
		generator.generate();

		Properties win32Config = Utils.loadProperties(buildFolder.getFile("productRootFiles/win32.win32.x86/configuration/config.ini"));
		Properties linuxConfig = Utils.loadProperties(buildFolder.getFile("productRootFiles/linux.gtk.x86/configuration/config.ini"));

		String bundlesList = win32Config.getProperty("osgi.bundles");
		assertTrue(bundlesList.indexOf('a') > -1);
		assertTrue(bundlesList.indexOf('b') > -1);
		assertTrue(bundlesList.indexOf('d') > -1);

		bundlesList = linuxConfig.getProperty("osgi.bundles");
		assertTrue(bundlesList.indexOf('a') > -1);
		assertTrue(bundlesList.indexOf('b') > -1);
		assertTrue(bundlesList.indexOf('c') > -1);
	}
}
