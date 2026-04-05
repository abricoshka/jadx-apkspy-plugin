package jadx.plugins.apkspy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.plugins.apkspy.model.ChangeCache;
import jadx.plugins.apkspy.model.ClassBreakdown;
import jadx.plugins.apkspy.model.SmaliBreakdown;
import jadx.plugins.apkspy.model.SmaliMethod;
import jadx.plugins.apkspy.utils.Util;

public class ApkSpy {

	private static final Logger LOG = LoggerFactory.getLogger(ApkSpy.class);
	private static final String STUB_CACHE_VERSION = "apkspy-stub-v5";

	private static String getClasspath(String... libs) {
	    return Arrays.stream(libs)
	            .map(lib -> new File(lib).isAbsolute()
	                    ? lib
	                    : String.join(File.separator, "..", "libs", lib))
	            .collect(Collectors.joining(File.pathSeparator));
	}

	private static String findDeclaredTypeName(String src) {
	    Matcher m = Pattern.compile("\\bpublic\\s+(?:class|interface|enum|@interface)\\s+(\\w+)\\b").matcher(src);
	    if (m.find()) {
	        return m.group(1);
	    }
	
	    m = Pattern.compile("\\b(?:class|interface|enum|@interface)\\s+(\\w+)\\b").matcher(src);
	    if (m.find()) {
	        return m.group(1);
	    }
	
	    throw new IllegalStateException("Could not determine declared type name");
	}

	private static String findLatestAndroidJars(final String sdkPath) {
		File platformDir = new File(sdkPath, "platforms");
		if (platformDir.exists() && platformDir.listFiles() != null) {
			int lastVersion = -1;
			for (File androidVersionDir : platformDir.listFiles()) {
				if (androidVersionDir.isDirectory() && androidVersionDir.getName().startsWith("android-")) {
					try {
						int version = Integer.parseInt(androidVersionDir.getName().substring(8));
						if (version > lastVersion) {
							lastVersion = version;
						}
					} catch (NumberFormatException ignore) {
						// ignore
					}
				}
			}
			if (lastVersion > -1) {
				return new File(platformDir, "android-" + lastVersion).getAbsolutePath();
			}
		}
		return null;
	}

	private static void writeLine(OutputStream out, String message) throws IOException {
		out.write(message.getBytes(StandardCharsets.UTF_8));
		out.write('\n');
	}

	private static Set<String> toJarEntries(Set<String> classNames) {
		return classNames.stream()
				.map(className -> className.replace('.', '/') + ".class")
				.collect(Collectors.toCollection(HashSet::new));
	}

	private static String buildStubSuffix(Map<String, ClassBreakdown> classes) {
		List<String> sortedNames = new ArrayList<>(classes.keySet());
		Collections.sort(sortedNames);

		StringBuilder fingerprint = new StringBuilder(STUB_CACHE_VERSION).append('\n');
		for (String className : sortedNames) {
			fingerprint.append(className)
					.append("::")
					.append(classes.get(className).toString().hashCode())
					.append('\n');
		}
		return Integer.toHexString(fingerprint.toString().hashCode());
	}

	private static Path getStubJarPath(File apk, Map<String, ClassBreakdown> classes) {
		return Paths.get(System.getProperty("java.io.tmpdir"), "apkSpy",
				apk.getName().replace('.', '_') + "_" + buildStubSuffix(classes) + "_stub.jar");
	}

	private static boolean isExcludedClassEntry(String entryName, Set<String> classNames) {
		if (!entryName.endsWith(".class")) {
			return false;
		}
		String classEntry = entryName.substring(0, entryName.length() - ".class".length()).replace('\\', '/');
		for (String className : classNames) {
			String internalName = className.replace('.', '/');
			if (classEntry.equals(internalName) || classEntry.startsWith(internalName + "$")) {
				return true;
			}
		}
		return false;
	}

	private static boolean shouldRegenerateStubJar(Path stubPath, Set<String> classNames, OutputStream out)
			throws IOException {
		int backslashEntries = 0;
		List<String> excludedEntries = new ArrayList<>();

		try (JarFile jarFile = new JarFile(stubPath.toFile())) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry.getName().indexOf('\\') >= 0) {
					backslashEntries++;
				}
				if (isExcludedClassEntry(entry.getName(), classNames)) {
					excludedEntries.add(entry.getName().replace('\\', '/'));
				}
			}
		} catch (IOException e) {
			writeLine(out, "[ApkSpy] Regenerating stub.jar because it could not be read: " + e.getMessage());
			return true;
		}

		if (backslashEntries > 0) {
			writeLine(out, "[ApkSpy] Regenerating stub.jar because it contains " + backslashEntries
					+ " non-portable jar entries with '\\'.");
			return true;
		}
		if (!excludedEntries.isEmpty()) {
			writeLine(out, "[ApkSpy] Regenerating stub.jar because compile targets are still bundled: "
					+ String.join(", ", excludedEntries));
			return true;
		}
		return false;
	}

	private static void ensureStubJar(File apk, Path stubPath, OutputStream out, Map<String, ClassBreakdown> classes)
			throws IOException, InterruptedException {
		Files.createDirectories(stubPath.getParent());
		if (!Files.exists(stubPath) || shouldRegenerateStubJar(stubPath, classes.keySet(), out)) {
			JarGenerator.generateStubJar(apk, stubPath.toFile(), out, classes);
		}
	}

	private static void logStubJarDiagnostics(Path stubPath, Set<String> classNames, OutputStream out) throws IOException {
		Set<String> excludedEntries = toJarEntries(classNames);
		List<String> bundledExcludedEntries = new ArrayList<>();
		List<String> sampleEntries = new ArrayList<>();
		int classEntries = 0;
		int backslashEntries = 0;

		try (JarFile jarFile = new JarFile(stubPath.toFile())) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String normalizedEntry = entry.getName().replace('\\', '/');
				if (entry.getName().indexOf('\\') >= 0) {
					backslashEntries++;
				}
				if (normalizedEntry.endsWith(".class")) {
					classEntries++;
					if (sampleEntries.size() < 15) {
						sampleEntries.add(normalizedEntry);
					}
				}
				if (excludedEntries.contains(normalizedEntry) || isExcludedClassEntry(normalizedEntry, classNames)) {
					bundledExcludedEntries.add(normalizedEntry);
				}
			}
		}

		writeLine(out, "[ApkSpy] stub.jar path: " + stubPath.toAbsolutePath());
		writeLine(out, "[ApkSpy] stub.jar size: " + Files.size(stubPath) + " bytes");
		writeLine(out, "[ApkSpy] stub.jar class entries: " + classEntries);
		writeLine(out, "[ApkSpy] stub.jar entries using '\\': " + backslashEntries);
		if (bundledExcludedEntries.isEmpty()) {
			writeLine(out, "[ApkSpy] stub.jar excludes compile targets as expected.");
		} else {
			writeLine(out, "[ApkSpy] stub.jar still contains compile targets: "
					+ String.join(", ", bundledExcludedEntries));
		}
		if (!sampleEntries.isEmpty()) {
			writeLine(out, "[ApkSpy] stub.jar sample entries: " + String.join(", ", sampleEntries));
		}
	}

	private static void mergeResourceReferences(Map<String, Set<String>> mergedReferences,
			Map<String, Set<String>> additionalReferences) {
		for (Map.Entry<String, Set<String>> referenceEntry : additionalReferences.entrySet()) {
			mergedReferences.computeIfAbsent(referenceEntry.getKey(), key -> new HashSet<>())
					.addAll(referenceEntry.getValue());
		}
	}

	private static String getPackageName(String className) {
		return className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
	}

	private static void configureBuildGradle(Path gradleBuildPath, String applicationId,
			Map<String, ClassBreakdown> classes, OutputStream out) throws IOException {
		String buildGradle = Files.readString(gradleBuildPath, StandardCharsets.UTF_8);
		CompileDependencyResolver.DependencySelection dependencySelection = CompileDependencyResolver.resolve(classes);
		buildGradle = buildGradle.replace("$APPLICATION_ID", applicationId)
				.replace("$APKSPY_DEPENDENCIES", dependencySelection.getRenderedDependencies());
		Files.writeString(gradleBuildPath, buildGradle, StandardCharsets.UTF_8);

		writeLine(out, "[ApkSpy] dependency profile: " + dependencySelection.getProfileSummary());
		if (dependencySelection.usesAndroidX() && dependencySelection.usesSupport()) {
			writeLine(out, "[ApkSpy] dependency profile uses mixed AndroidX/support compileOnly fallbacks only.");
		}
	}

	private static void writeSources(Path sourceRoot, Map<String, ClassBreakdown> classes, OutputStream out) throws IOException {
		Map<String, Map<String, Set<String>>> packageResourceReferences = new HashMap<>();
		Set<String> packagesWithRealR = classes.entrySet().stream()
				.filter(entry -> "R".equals(entry.getValue().getSimpleName())
						|| entry.getKey().endsWith(".R")
						|| "R".equals(entry.getKey()))
				.map(entry -> getPackageName(entry.getKey()))
				.collect(Collectors.toSet());

		for (Map.Entry<String, ClassBreakdown> entry : classes.entrySet()) {
			String className = entry.getKey();
			ClassBreakdown content = entry.getValue();
			String packageName = getPackageName(className);
			String simpleName = content.getSimpleName() != null ? content.getSimpleName()
					: className.substring(className.lastIndexOf('.') + 1);

			Path folder = packageName.isEmpty()
					? sourceRoot
					: sourceRoot.resolve(packageName.replace('.', File.separatorChar));
			Files.createDirectories(folder);

			CompileSourceNormalizer.NormalizedSource normalizedSource = CompileSourceNormalizer.normalize(className,
					content.toString());
			for (String diagnostic : normalizedSource.getDiagnostics()) {
				writeLine(out, "[ApkSpy] " + className + ": " + diagnostic);
			}
			if (!"R".equals(simpleName) && !packagesWithRealR.contains(packageName)
					&& !normalizedSource.getRReferences().isEmpty()) {
				mergeResourceReferences(
						packageResourceReferences.computeIfAbsent(packageName, key -> new HashMap<>()),
						normalizedSource.getRReferences());
			}

			Path file = folder.resolve(simpleName + ".java");
			Files.writeString(file, normalizedSource.getSource(), StandardCharsets.UTF_8);
			writeLine(out, "[ApkSpy] source: " + file.toAbsolutePath());
		}

		for (Map.Entry<String, Map<String, Set<String>>> packageEntry : packageResourceReferences.entrySet()) {
			String packageName = packageEntry.getKey();
			Path folder = packageName.isEmpty()
					? sourceRoot
					: sourceRoot.resolve(packageName.replace('.', File.separatorChar));
			Files.createDirectories(folder);

			Path rFile = folder.resolve("R.java");
			Files.writeString(rFile, CompileSourceNormalizer.renderRSource(packageName, packageEntry.getValue()),
					StandardCharsets.UTF_8);
			writeLine(out, "[ApkSpy] compile-time R stub: " + rFile.toAbsolutePath());
		}
	}

	private static Map<String, Path> indexSmaliFiles(List<Path> roots) throws IOException {
		Map<String, Path> index = new HashMap<>();
		for (Path root : roots) {
			try (var paths = Files.walk(root)) {
				List<Path> smaliFiles = paths
						.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".smali"))
						.collect(Collectors.toList());
				for (Path smaliFile : smaliFiles) {
					String content = Files.readString(smaliFile, StandardCharsets.UTF_8);
					String className = SmaliBreakdown.breakdown(content).getClassName();
					if (!className.isEmpty()) {
						index.putIfAbsent(className, smaliFile);
					}
				}
			}
		}
		return index;
	}

	private static Map<Path, Path> mapGeneratedToOriginalRoots(List<Path> generatedRoots) {
		Map<Path, Path> rootMap = new HashMap<>();
		for (Path generatedRoot : generatedRoots) {
			rootMap.put(generatedRoot, Paths.get(generatedRoot.toString().replace("generated", "original")));
		}
		return rootMap;
	}

	private static Path findRoot(Path file, Set<Path> roots) {
		for (Path root : roots) {
			if (file.startsWith(root)) {
				return root;
			}
		}
		return null;
	}

	private static boolean isSyntheticNestedClass(String ownerClassName, String candidateClassName) {
		if (!candidateClassName.startsWith(ownerClassName + "$")) {
			return false;
		}
		String suffix = candidateClassName.substring(ownerClassName.length() + 1);
		return !suffix.isEmpty() && (Character.isDigit(suffix.charAt(0)) || suffix.contains("$$"));
	}

	public static boolean lint(String apk, String className, ClassBreakdown content, String sdkPath, String jdkLocation, OutputStream out)
			throws IOException, InterruptedException {
		LOG.info("Linting: {}", apk);
		File modifyingApk = new File(apk);
		Path root = Paths.get("project-tmp");
		Map<String, ClassBreakdown> classes = Collections.singletonMap(className, content);

		Util.attemptDelete(root.toFile());
		copyProjectTemplate(root.toFile());

		sdkPath = sdkPath.replace("\\", "\\\\");
		Files.write(root.resolve("local.properties"),
				("sdk.dir=" + sdkPath).getBytes(StandardCharsets.UTF_8));

		Path gradleBuildPath = root.resolve(Paths.get("app", "build.gradle"));
		configureBuildGradle(gradleBuildPath, "lint", classes, out);

		writeSources(root.resolve(Paths.get("app", "src", "main", "java")), classes, out);

		Path stubPath = getStubJarPath(modifyingApk, classes);
		ensureStubJar(modifyingApk, stubPath, out, classes);

		Path libsDir = root.resolve(Paths.get("app", "libs"));
		Files.createDirectories(libsDir);
		Path projectStubPath = libsDir.resolve("stub.jar");
		Files.copy(stubPath, projectStubPath, StandardCopyOption.REPLACE_EXISTING);
		logStubJarDiagnostics(projectStubPath, classes.keySet(), out);

		if (!Util.isWindows()) {
			Runtime.getRuntime().exec("chmod +x project-tmp/gradlew").waitFor();
		}

		out.write("Started compile...\n".getBytes(StandardCharsets.UTF_8));
		int code = Util.system(root.toFile(), jdkLocation, out,
				new File("project-tmp").getAbsolutePath() + File.separator + (Util.isWindows() ? "gradlew.bat" : "gradlew"),
				":app:compileDebugJavaWithJavac");

		Util.attemptDelete(root.toFile());

		return code == 0;
	}

	public static boolean merge(String apk, String outputLocation, String sdkPath, String jdkLocation, String apktoolLocation,
			String applicationId,
			OutputStream out)
			throws IOException, InterruptedException {
		sdkPath = sdkPath.replace("\\", "\\\\");

		LOG.info("Merging: {}", apk);
		File modifyingApk = new File(apk);

		Util.attemptDelete(new File("project-tmp"));
		Util.attemptDelete(new File("smali"));

		copyProjectTemplate(new File("project-tmp"));

		Files.write(Paths.get("project-tmp", "local.properties"),
				("sdk.dir=" + sdkPath).getBytes(StandardCharsets.UTF_8));

		Map<String, ClassBreakdown> classes = ChangeCache.getInstance().getChanges();
		Path gradleBuildPath = Paths.get("project-tmp", "app", "build.gradle");
		configureBuildGradle(gradleBuildPath, applicationId, classes, out);

		writeSources(Paths.get("project-tmp", "app", "src", "main", "java"), classes, out);

		Path stubPath = getStubJarPath(modifyingApk, classes);
		ensureStubJar(modifyingApk, stubPath, out, classes);
		Files.createDirectories(Paths.get("project-tmp", "app", "libs"));

		if (!Files.exists(Paths.get("project-tmp", "app", "libs", "stub.jar"))) {
			// we check if it doesn't already exist, in case gradle has a lock on it and it
			// couldn't be deleted before
			Files.copy(stubPath, Paths.get("project-tmp", "app", "libs", "stub.jar"));
		}
		logStubJarDiagnostics(Paths.get("project-tmp", "app", "libs", "stub.jar"), classes.keySet(), out);

		if (!Util.isWindows()) {
			Runtime.getRuntime().exec("chmod +x project-tmp/gradlew").waitFor();
		}

		if (Util.system(new File("project-tmp"), jdkLocation, out, new File("project-tmp").getAbsolutePath() + File.separator
				+ (Util.isWindows() ? "gradlew.bat" : "gradlew"), "build") != 0) {
			Util.attemptDelete(new File("project-tmp"));
			return false;
		}

		Files.copy(Paths.get("project-tmp", "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
				Paths.get("generated.apk"), StandardCopyOption.REPLACE_EXISTING);
		Util.attemptDelete(new File("project-tmp"));

		ApktoolWrapper.decode(Paths.get("generated.apk"), apktoolLocation, jdkLocation, "generated", false, out);
		Files.delete(Paths.get("generated.apk"));

		ApktoolWrapper.decode(modifyingApk.toPath(), apktoolLocation, jdkLocation, "original", true, out);

		List<Path> smaliFolders = Files.list(Paths.get("smali", "generated"))
				.filter(path -> Files.isDirectory(path) && path.getFileName().toString().startsWith("smali"))
				.collect(Collectors.toList());
		List<Path> destinationFolders = smaliFolders.stream()
				.map(path -> Paths.get(path.toString().replace("generated", "original"))).collect(Collectors.toList());
		Map<Path, Path> generatedToOriginalRoots = mapGeneratedToOriginalRoots(smaliFolders);
		Map<String, Path> generatedIndex = indexSmaliFiles(smaliFolders);
		Map<String, Path> originalIndex = indexSmaliFiles(destinationFolders);

		for (Map.Entry<String, ClassBreakdown> classEntry : classes.entrySet()) {
			String className = classEntry.getKey();
			ClassBreakdown breakdown = classEntry.getValue();
			Path generatedClassPath = generatedIndex.get(className);

			if (generatedClassPath == null) {
				writeLine(out, "[ApkSpy] Could not locate generated smali for " + className);
				continue;
			}

			Path originalClassPath = originalIndex.get(className);
			if (originalClassPath != null) {
				LOG.info("Merging smali for class: {}", className);

				String modifiedContent = Files.readString(generatedClassPath, StandardCharsets.UTF_8);
				String originalContent = Files.readString(originalClassPath, StandardCharsets.UTF_8);
				SmaliBreakdown modifiedSmali = SmaliBreakdown.breakdown(modifiedContent);
				List<SmaliMethod> methods = modifiedSmali.getChangedMethods(breakdown);

				writeLine(out, "[ApkSpy] Merging " + methods.size() + " method(s) for " + className);
				StringBuilder builder = new StringBuilder(originalContent);
				for (SmaliMethod method : methods) {
					SmaliBreakdown originalSmali = SmaliBreakdown.breakdown(builder.toString());
					SmaliMethod equivalentMethod = originalSmali.getEquivalentMethod(method);
					if (equivalentMethod == null) {
						writeLine(out, "[ApkSpy] Could not find matching original method for " + className + ": "
								+ method.getContent().split("\n")[0]);
						continue;
					}

					builder.delete(equivalentMethod.getStart(), equivalentMethod.getEnd());
					builder.insert(equivalentMethod.getStart(), method.getContent());
				}

				Files.writeString(originalClassPath, builder.toString(), StandardOpenOption.TRUNCATE_EXISTING,
						StandardOpenOption.WRITE);

				for (Map.Entry<String, Path> generatedClassEntry : generatedIndex.entrySet()) {
					String generatedClassName = generatedClassEntry.getKey();
					if (!isSyntheticNestedClass(className, generatedClassName)) {
						continue;
					}

					Path sourcePath = generatedClassEntry.getValue();
					Path generatedRoot = findRoot(sourcePath, generatedToOriginalRoots.keySet());
					if (generatedRoot == null) {
						continue;
					}

					Path destinationRoot = generatedToOriginalRoots.get(generatedRoot);
					Path destinationPath = destinationRoot.resolve(generatedRoot.relativize(sourcePath));
					if (originalIndex.containsKey(generatedClassName)) {
						writeLine(out, "[ApkSpy] Skipping nested class overwrite due to name collision: "
								+ generatedClassName);
						continue;
					}
					Files.createDirectories(destinationPath.getParent());
					Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
					originalIndex.put(generatedClassName, destinationPath);
				}
				continue;
			}

			writeLine(out, "[ApkSpy] Copying added class: " + className);
			for (Map.Entry<String, Path> generatedClassEntry : generatedIndex.entrySet()) {
				String generatedClassName = generatedClassEntry.getKey();
				if (!generatedClassName.equals(className) && !generatedClassName.startsWith(className + "$")) {
					continue;
				}

				Path sourcePath = generatedClassEntry.getValue();
				Path generatedRoot = findRoot(sourcePath, generatedToOriginalRoots.keySet());
				if (generatedRoot == null) {
					continue;
				}

				Path destinationRoot = generatedToOriginalRoots.get(generatedRoot);
				Path destinationPath = destinationRoot.resolve(generatedRoot.relativize(sourcePath));
				Files.createDirectories(destinationPath.getParent());
				Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
				originalIndex.put(generatedClassName, destinationPath);
			}
		}

		Set<String> deletions = ChangeCache.getInstance().getClassDeletions();
		for (String deletion : deletions) {
			// file might not exist, as we could delete temporary classes that we made in
			// between compilations in the editor

			for (Path path : destinationFolders) {
				if (Files.deleteIfExists(Paths.get(path.toAbsolutePath().toString(),
						deletion))) {
					break;
				}
			}
		}

		ApktoolWrapper.build(Paths.get("smali", "original"), apktoolLocation, jdkLocation, outputLocation, out);
		Util.attemptDelete(new File("smali"));

		Files.deleteIfExists(stubPath);

		out.write("Finished creating APK!".getBytes(StandardCharsets.UTF_8));
		return true;
	}

	private static void copyProjectTemplate(final File projectRoot) {
		String[] projectFiles = { "apkspy/default/app/src/main/res/values/styles.xml",
				"apkspy/default/app/src/main/AndroidManifest.xml",
				"apkspy/default/app/build.gradle",
				"apkspy/default/gradle/wrapper/gradle-wrapper.jar.zip", // rename this file later
				"apkspy/default/gradle/wrapper/gradle-wrapper.properties",
				"apkspy/default/build.gradle",
				"apkspy/default/gradle.properties",
				"apkspy/default/gradlew",
				"apkspy/default/gradlew.bat",
				"apkspy/default/settings.gradle" };
		if (!projectRoot.exists()) {
			projectRoot.mkdirs();
		}
		for (final String filename : projectFiles) {
			String targetFilename = filename.substring(15);
			if (targetFilename.lastIndexOf('/') > -1) {
				String dest = targetFilename.substring(0, targetFilename.lastIndexOf('/'));
				if (File.separatorChar != '/') {
					dest = dest.replace('/', File.separatorChar);
				}
				final File destDir = new File(projectRoot, dest);
				if (!destDir.exists()) {
					destDir.mkdirs();
				}
			}
			// keep jar as a file, protect it against gradle shadow plugin
			if (targetFilename.endsWith(".jar.zip")) {
				targetFilename = targetFilename.replace(".jar.zip", ".jar");
			}
			try (final InputStream in = ApkSpy.class.getClassLoader().getResourceAsStream(filename)) {
				final File targetFile = new File(projectRoot, targetFilename);
				FileUtils.copyInputStreamToFile(in, targetFile);
			} catch (final IOException e) {
				LOG.error("Could not copy project template: ", e);
			}
		}
	}
}
