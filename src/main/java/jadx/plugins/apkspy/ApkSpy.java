package jadx.plugins.apkspy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
	private static final int MAX_DIAGNOSTIC_PATHS = 20;
	private static final int FILE_PROGRESS_LOG_INTERVAL = 100;
	private static final long FILE_PROGRESS_HEARTBEAT_MS = TimeUnit.SECONDS.toMillis(5);
	private static final long POST_PROCESS_HEARTBEAT_MS = TimeUnit.SECONDS.toMillis(10);

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
		synchronized (out) {
			out.write(message.getBytes(StandardCharsets.UTF_8));
			out.write('\n');
			out.flush();
		}
	}

	private static void writeException(OutputStream out, String stage, Throwable throwable) throws IOException {
		writeLine(out, "[ApkSpy] ERROR during " + stage + ": " + throwable);
		StringWriter writer = new StringWriter();
		throwable.printStackTrace(new PrintWriter(writer));
		for (String line : writer.toString().split("\\r?\\n")) {
			if (!line.isEmpty()) {
				writeLine(out, "[ApkSpy]   " + line);
			}
		}
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

	static RebuildStrategy selectRebuildStrategy(Map<String, ClassBreakdown> ignoredClasses) {
		return RebuildStrategy.SMALI_ONLY_NO_RES;
	}

	private static void logRebuildStrategy(RebuildStrategy strategy, OutputStream out) throws IOException {
		writeLine(out, "[ApkSpy] rebuild strategy: " + strategy.getId());
		writeLine(out, "[ApkSpy] rebuild strategy reason: " + strategy.getDescription());
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

	static Map<String, Path> indexSmaliFiles(List<Path> roots, String label, OutputStream out,
			PostProcessHeartbeat heartbeat) throws IOException {
		Map<String, Path> index = new HashMap<>();
		Set<Path> visitedRoots = new HashSet<>();
		int processed = 0;
		int indexed = 0;
		long lastProgressLogAt = System.nanoTime();

		writeLine(out, "[ApkSpy] indexing " + label + " smali files from " + roots.size() + " root(s)");
		for (Path root : roots) {
			if (root == null) {
				writeLine(out, "[ApkSpy] skip missing " + label + " root: (null)");
				continue;
			}
			if (!Files.exists(root)) {
				writeLine(out, "[ApkSpy] skip missing " + label + " root: " + root.toAbsolutePath());
				continue;
			}
			Path normalizedRoot = toCanonicalOrAbsolutePath(root);
			if (!visitedRoots.add(normalizedRoot)) {
				writeLine(out, "[ApkSpy] Skipping duplicate smali root for " + label + ": "
						+ normalizedRoot.toAbsolutePath());
				continue;
			}
			writeLine(out, "[ApkSpy] indexing " + label + " root: " + normalizedRoot.toAbsolutePath());
			heartbeat.setStage("index " + label + " smali");
			heartbeat.setItem(normalizedRoot.toAbsolutePath().toString());
			try (var paths = Files.walk(normalizedRoot)) {
				var iterator = paths
						.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".smali"))
						.iterator();
				while (iterator.hasNext()) {
					Path smaliFile = iterator.next();
					heartbeat.setItem(smaliFile.toAbsolutePath().toString());
					processed++;
					String content = Files.readString(smaliFile, StandardCharsets.UTF_8);
					String className = SmaliBreakdown.breakdown(content).getClassName();
					if (!className.isEmpty()) {
						index.putIfAbsent(className, smaliFile);
						indexed++;
					}
					long now = System.nanoTime();
					if (processed % FILE_PROGRESS_LOG_INTERVAL == 0
							|| TimeUnit.NANOSECONDS.toMillis(now - lastProgressLogAt) >= FILE_PROGRESS_HEARTBEAT_MS) {
						writeLine(out, "[ApkSpy] indexing " + label + " progress: processed=" + processed
								+ ", indexed=" + indexed + ", current=" + smaliFile.toAbsolutePath());
						lastProgressLogAt = now;
					}
				}
			}
		}
		writeLine(out, "[ApkSpy] finished indexing " + label + " smali files: processed=" + processed
				+ ", indexed=" + indexed);
		return index;
	}

	static Map<Path, Path> mapGeneratedToOriginalRoots(List<Path> generatedRoots, OutputStream out) throws IOException {
		Map<Path, Path> rootMap = new LinkedHashMap<>();
		for (Path generatedRoot : generatedRoots) {
			Path originalRoot = Paths.get(generatedRoot.toString().replace("generated", "original"));
			rootMap.put(generatedRoot, originalRoot);
			writeLine(out, "[ApkSpy] original root "
					+ (Files.isDirectory(originalRoot) ? "exists" : "missing")
					+ ": " + originalRoot.toAbsolutePath());
		}
		return rootMap;
	}

	private static List<Path> collectExistingRoots(Map<Path, Path> rootMap) {
		return rootMap.values().stream()
				.filter(path -> path != null && Files.isDirectory(path))
				.collect(Collectors.toList());
	}

	static UnknownFilesSnapshot collectUnknownFiles(Path decodeRoot) throws IOException {
		Map<String, Path> filesByApkPath = new LinkedHashMap<>();
		Path normalizedDecodeRoot = toCanonicalOrAbsolutePath(decodeRoot);
		Path unknownRoot = normalizedDecodeRoot.resolve("unknown");
		if (Files.isDirectory(unknownRoot)) {
			try (var paths = Files.walk(unknownRoot)) {
				for (Path file : paths.filter(Files::isRegularFile).collect(Collectors.toList())) {
					String apkPath = normalizeRelativePath(unknownRoot.relativize(file));
					filesByApkPath.put(apkPath, file);
				}
			}
		}

		Map<String, String> apktoolMappings = new LinkedHashMap<>();
		Path apktoolYml = normalizedDecodeRoot.resolve("apktool.yml");
		if (Files.exists(apktoolYml)) {
			apktoolMappings.putAll(parseUnknownFilesSection(Files.readString(apktoolYml, StandardCharsets.UTF_8)));
		}
		return new UnknownFilesSnapshot(normalizedDecodeRoot, filesByApkPath, apktoolMappings);
	}

	private static void logUnknownFiles(String stage, UnknownFilesSnapshot snapshot, OutputStream out) throws IOException {
		writeLine(out, "[ApkSpy] " + stage + " decode root: " + snapshot.getDecodeRoot().toAbsolutePath());
		writeLine(out, "[ApkSpy] after collect " + stage + " unknown files: fileCount="
				+ snapshot.getFilesByApkPath().size() + ", mappingCount=" + snapshot.getApktoolMappings().size());
		writeLine(out, "[ApkSpy] " + stage + " unknown files: "
				+ formatDiagnosticPaths(snapshot.getFilesByApkPath().keySet()));
		writeLine(out, "[ApkSpy] " + stage + " apktool.yml unknownFiles: "
				+ formatDiagnosticPaths(snapshot.getApktoolMappings().keySet()));
	}

	static void mergeUnknownFiles(UnknownFilesSnapshot generatedUnknown, Path originalRoot,
			UnknownFilesSnapshot originalUnknown, OutputStream out, PostProcessHeartbeat heartbeat) throws IOException {
		writeLine(out, "[ApkSpy] before unknown files merge/copy");
		int copied = 0;
		int skipped = 0;
		int processed = 0;
		long lastProgressLogAt = System.nanoTime();

		for (Map.Entry<String, Path> generatedEntry : generatedUnknown.getFilesByApkPath().entrySet()) {
			String apkPath = generatedEntry.getKey();
			Path source = generatedEntry.getValue();
			Path destination = originalRoot.resolve("unknown").resolve(apkPath.replace('/', java.io.File.separatorChar));
			heartbeat.setStage("unknown files merge/copy");
			heartbeat.setItem(apkPath);
			processed++;
			if (!Files.exists(source)) {
				writeLine(out, "[ApkSpy] Skipping unknown file " + apkPath + ": generated source missing");
				skipped++;
				continue;
			}

			Files.createDirectories(destination.getParent());
			if (Files.exists(destination) && Files.mismatch(source, destination) == -1L) {
				writeLine(out, "[ApkSpy] Skipping unknown file " + apkPath + ": destination already identical at "
						+ destination.toAbsolutePath());
				skipped++;
				continue;
			}

			Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
			writeLine(out, "[ApkSpy] Copied unknown file " + apkPath + " -> " + destination.toAbsolutePath());
			copied++;
			long now = System.nanoTime();
			if (processed % FILE_PROGRESS_LOG_INTERVAL == 0
					|| TimeUnit.NANOSECONDS.toMillis(now - lastProgressLogAt) >= FILE_PROGRESS_HEARTBEAT_MS) {
				writeLine(out, "[ApkSpy] unknown files merge progress: processed=" + processed
						+ ", copied=" + copied + ", skipped=" + skipped + ", current=" + apkPath);
				lastProgressLogAt = now;
			}
		}

		Set<String> preservedOriginalOnly = new LinkedHashSet<>(originalUnknown.getFilesByApkPath().keySet());
		preservedOriginalOnly.removeAll(generatedUnknown.getFilesByApkPath().keySet());
		if (!preservedOriginalOnly.isEmpty()) {
			writeLine(out, "[ApkSpy] Preserving original-only unknown files: "
					+ formatDiagnosticPaths(preservedOriginalOnly));
		}

		for (String generatedMappingPath : generatedUnknown.getApktoolMappings().keySet()) {
			if (!generatedUnknown.getFilesByApkPath().containsKey(generatedMappingPath)
					&& !originalUnknown.getFilesByApkPath().containsKey(generatedMappingPath)) {
				writeLine(out, "[ApkSpy] Skipping unknownFiles mapping " + generatedMappingPath
						+ ": no matching file in generated or original decode tree");
				skipped++;
			}
		}

		Path originalApktoolYml = originalRoot.resolve("apktool.yml");
		if (Files.exists(originalApktoolYml)) {
			String originalApktoolContent = Files.readString(originalApktoolYml, StandardCharsets.UTF_8);
			String mergedApktoolContent = mergeUnknownFilesSection(originalApktoolContent,
					generatedUnknown.getApktoolMappings());
			if (!mergedApktoolContent.equals(originalApktoolContent)) {
				Files.writeString(originalApktoolYml, mergedApktoolContent, StandardCharsets.UTF_8);
				writeLine(out, "[ApkSpy] Updated original apktool.yml unknownFiles section");
			} else {
				writeLine(out, "[ApkSpy] apktool.yml unknownFiles section unchanged");
			}
		} else {
			writeLine(out, "[ApkSpy] Skipping unknownFiles mapping merge: original apktool.yml missing");
		}

		writeLine(out, "[ApkSpy] unknown files merge summary: copied=" + copied + ", skipped=" + skipped);
	}

	static void mergeUnknownFiles(UnknownFilesSnapshot generatedUnknown, Path originalRoot,
			UnknownFilesSnapshot originalUnknown, OutputStream out) throws IOException {
		try (PostProcessHeartbeat heartbeat = PostProcessHeartbeat.start(out)) {
			heartbeat.setStage("unknown files merge/copy");
			heartbeat.setItem("(test/helper)");
			mergeUnknownFiles(generatedUnknown, originalRoot, originalUnknown, out, heartbeat);
		}
	}

	static Map<String, String> parseUnknownFilesSection(String apktoolYmlContent) {
		Map<String, String> mappings = new LinkedHashMap<>();
		String[] lines = apktoolYmlContent.split("\\r?\\n", -1);
		boolean inSection = false;
		int sectionIndent = -1;
		for (String line : lines) {
			String trimmed = line.trim();
			if (!inSection) {
				if ("unknownFiles:".equals(trimmed)) {
					inSection = true;
					sectionIndent = countLeadingWhitespace(line);
				}
				continue;
			}

			if (!trimmed.isEmpty() && countLeadingWhitespace(line) <= sectionIndent) {
				break;
			}
			if (trimmed.isEmpty()) {
				continue;
			}

			int colonIndex = trimmed.indexOf(':');
			if (colonIndex <= 0) {
				continue;
			}
			String key = trimmed.substring(0, colonIndex).trim();
			String value = trimmed.substring(colonIndex + 1).trim();
			if ((key.startsWith("'") && key.endsWith("'")) || (key.startsWith("\"") && key.endsWith("\""))) {
				key = key.substring(1, key.length() - 1);
			}
			mappings.put(key, value);
		}
		return mappings;
	}

	static String mergeUnknownFilesSection(String originalApktoolContent, Map<String, String> generatedMappings) {
		if (generatedMappings.isEmpty()) {
			return originalApktoolContent;
		}

		Map<String, String> mergedMappings = new LinkedHashMap<>(parseUnknownFilesSection(originalApktoolContent));
		mergedMappings.putAll(generatedMappings);

		String lineSeparator = originalApktoolContent.contains("\r\n") ? "\r\n" : "\n";
		List<String> originalLines = Arrays.asList(originalApktoolContent.split("\\r?\\n", -1));
		int sectionStart = -1;
		int sectionEnd = originalLines.size();
		int sectionIndent = -1;

		for (int i = 0; i < originalLines.size(); i++) {
			String line = originalLines.get(i);
			if (sectionStart < 0) {
				if ("unknownFiles:".equals(line.trim())) {
					sectionStart = i;
					sectionIndent = countLeadingWhitespace(line);
				}
				continue;
			}

			String trimmed = line.trim();
			if (!trimmed.isEmpty() && countLeadingWhitespace(line) <= sectionIndent) {
				sectionEnd = i;
				break;
			}
		}

		List<String> mergedLines = new ArrayList<>();
		if (sectionStart < 0) {
			mergedLines.addAll(originalLines);
			if (!mergedLines.isEmpty() && !mergedLines.get(mergedLines.size() - 1).isEmpty()) {
				mergedLines.add("");
			}
			mergedLines.addAll(renderUnknownFilesSectionLines(mergedMappings));
		} else {
			mergedLines.addAll(originalLines.subList(0, sectionStart));
			mergedLines.addAll(renderUnknownFilesSectionLines(mergedMappings));
			mergedLines.addAll(originalLines.subList(sectionEnd, originalLines.size()));
		}
		return String.join(lineSeparator, mergedLines);
	}

	private static List<String> renderUnknownFilesSectionLines(Map<String, String> mappings) {
		List<String> lines = new ArrayList<>();
		lines.add("unknownFiles:");
		for (Map.Entry<String, String> mapping : mappings.entrySet()) {
			lines.add("  " + mapping.getKey() + ": " + mapping.getValue());
		}
		return lines;
	}

	private static int countLeadingWhitespace(String line) {
		int index = 0;
		while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
			index++;
		}
		return index;
	}

	private static String normalizeRelativePath(Path relativePath) {
		return relativePath.toString().replace('\\', '/');
	}

	private static Path toCanonicalOrAbsolutePath(Path path) throws IOException {
		try {
			return path.toRealPath();
		} catch (IOException ignored) {
			return path.toAbsolutePath().normalize();
		}
	}

	private static String formatDiagnosticPaths(Set<String> paths) {
		if (paths.isEmpty()) {
			return "(none)";
		}
		List<String> sample = paths.stream().limit(MAX_DIAGNOSTIC_PATHS).collect(Collectors.toList());
		if (paths.size() > sample.size()) {
			return String.join(", ", sample) + " ... (" + (paths.size() - sample.size()) + " more)";
		}
		return String.join(", ", sample);
	}

	private static Path findRoot(Path file, Set<Path> roots) {
		for (Path root : roots) {
			if (file.startsWith(root)) {
				return root;
			}
		}
		return null;
	}

	static Path prepareMergeDestination(Path sourcePath, Map<Path, Path> generatedToOriginalRoots, OutputStream out)
			throws IOException {
		Path generatedRoot = findRoot(sourcePath, generatedToOriginalRoots.keySet());
		if (generatedRoot == null) {
			return null;
		}
		Path destinationRoot = generatedToOriginalRoots.get(generatedRoot);
		if (destinationRoot == null) {
			return null;
		}
		if (!Files.isDirectory(destinationRoot)) {
			Files.createDirectories(destinationRoot);
			writeLine(out, "[ApkSpy] created missing root for merge output: " + destinationRoot.toAbsolutePath());
		}
		Path destinationPath = destinationRoot.resolve(generatedRoot.relativize(sourcePath));
		Files.createDirectories(destinationPath.getParent());
		return destinationPath;
	}

	private static boolean isSyntheticNestedClass(String ownerClassName, String candidateClassName) {
		if (!candidateClassName.startsWith(ownerClassName + "$")) {
			return false;
		}
		String suffix = candidateClassName.substring(ownerClassName.length() + 1);
		return !suffix.isEmpty() && (Character.isDigit(suffix.charAt(0)) || suffix.contains("$$"));
	}

	static final class UnknownFilesSnapshot {
		private final Path decodeRoot;
		private final Map<String, Path> filesByApkPath;
		private final Map<String, String> apktoolMappings;

		UnknownFilesSnapshot(Path decodeRoot, Map<String, Path> filesByApkPath, Map<String, String> apktoolMappings) {
			this.decodeRoot = decodeRoot;
			this.filesByApkPath = filesByApkPath;
			this.apktoolMappings = apktoolMappings;
		}

		Path getDecodeRoot() {
			return decodeRoot;
		}

		Map<String, Path> getFilesByApkPath() {
			return filesByApkPath;
		}

		Map<String, String> getApktoolMappings() {
			return apktoolMappings;
		}
	}

	enum RebuildStrategy {
		SMALI_ONLY_NO_RES("smali-only-no-res",
				"Pipeline changes smali/classes/unknown files only, so preserve original resources and avoid OEM/private resource aapt2 relinking.") {
			@Override
			boolean decodeResources() {
				return false;
			}
		};

		private final String id;
		private final String description;

		RebuildStrategy(String id, String description) {
			this.id = id;
			this.description = description;
		}

		String getId() {
			return id;
		}

		String getDescription() {
			return description;
		}

		abstract boolean decodeResources();
	}

	static final class PostProcessHeartbeat implements AutoCloseable {
		private final AtomicBoolean running = new AtomicBoolean(true);
		private final AtomicReference<String> stage = new AtomicReference<>("starting");
		private final AtomicReference<String> item = new AtomicReference<>("(none)");
		private final Thread thread;

		private PostProcessHeartbeat(OutputStream out) {
			thread = new Thread(() -> {
				while (running.get()) {
					try {
						Thread.sleep(POST_PROCESS_HEARTBEAT_MS);
						if (running.get()) {
							writeLine(out, "[ApkSpy] post-process heartbeat: stage=" + stage.get()
									+ ", item=" + item.get());
						}
					} catch (InterruptedException ignore) {
						Thread.currentThread().interrupt();
						break;
					} catch (IOException ioException) {
						break;
					}
				}
			}, "apkspy-post-process-heartbeat");
			thread.setDaemon(true);
			thread.start();
		}

		static PostProcessHeartbeat start(OutputStream out) {
			return new PostProcessHeartbeat(out);
		}

		void setStage(String stage) {
			this.stage.set(stage);
		}

		void setItem(String item) {
			this.item.set(item);
		}

		@Override
		public void close() {
			running.set(false);
			thread.interrupt();
		}
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

		RebuildStrategy rebuildStrategy = selectRebuildStrategy(classes);
		logRebuildStrategy(rebuildStrategy, out);

		ApktoolWrapper.decode(Paths.get("generated.apk"), apktoolLocation, jdkLocation, "generated", false, out);
		writeLine(out, "[ApkSpy] after decode generated: smali/generated (resources intentionally skipped with -r)");
		UnknownFilesSnapshot generatedUnknown = collectUnknownFiles(Paths.get("smali", "generated"));
		logUnknownFiles("generated", generatedUnknown, out);
		Files.delete(Paths.get("generated.apk"));

		ApktoolWrapper.decode(modifyingApk.toPath(), apktoolLocation, jdkLocation, "original",
				rebuildStrategy.decodeResources(), out);
		writeLine(out, "[ApkSpy] after decode original: smali/original "
				+ (rebuildStrategy.decodeResources()
						? "(resources kept for rebuild)"
						: "(resources intentionally skipped for smali-only rebuild)"));
		UnknownFilesSnapshot originalUnknown = collectUnknownFiles(Paths.get("smali", "original"));
		logUnknownFiles("original", originalUnknown, out);

		try (PostProcessHeartbeat heartbeat = PostProcessHeartbeat.start(out)) {
			try {
				heartbeat.setStage("collect generated smali roots");
				heartbeat.setItem(Paths.get("smali", "generated").toAbsolutePath().toString());
				writeLine(out, "[ApkSpy] collecting generated smali roots");
				List<Path> smaliFolders;
				try (var generatedRoots = Files.list(Paths.get("smali", "generated"))) {
					smaliFolders = generatedRoots
							.filter(path -> Files.isDirectory(path) && path.getFileName().toString().startsWith("smali"))
							.collect(Collectors.toList());
				}
				writeLine(out, "[ApkSpy] collected generated smali roots: " + smaliFolders.size() + " -> "
						+ smaliFolders.stream().map(path -> path.getFileName().toString()).collect(Collectors.joining(", ")));

				heartbeat.setStage("map original smali roots");
				List<Path> destinationFolders = smaliFolders.stream()
						.map(path -> Paths.get(path.toString().replace("generated", "original"))).collect(Collectors.toList());
				Map<Path, Path> generatedToOriginalRoots = mapGeneratedToOriginalRoots(smaliFolders, out);
				List<Path> existingOriginalRoots = collectExistingRoots(generatedToOriginalRoots);
				writeLine(out, "[ApkSpy] mapped original smali roots: " + destinationFolders.size() + " -> "
						+ destinationFolders.stream().map(path -> path.getFileName().toString())
								.collect(Collectors.joining(", ")));
				writeLine(out, "[ApkSpy] existing original smali roots for indexing: " + existingOriginalRoots.size()
						+ " -> " + existingOriginalRoots.stream().map(path -> path.getFileName().toString())
								.collect(Collectors.joining(", ")));

				Map<String, Path> generatedIndex = indexSmaliFiles(smaliFolders, "generated", out, heartbeat);
				Map<String, Path> originalIndex = indexSmaliFiles(existingOriginalRoots, "original", out, heartbeat);
				writeLine(out, "[ApkSpy] before smali merge: generatedSmaliDirs=" + smaliFolders.size()
						+ ", originalSmaliDirs=" + existingOriginalRoots.size()
						+ ", generatedClasses=" + generatedIndex.size()
						+ ", originalClasses=" + originalIndex.size());

				heartbeat.setStage("smali merge");
				int mergedClasses = 0;
				int copiedClasses = 0;
				int skippedClasses = 0;
				int classProgress = 0;
				for (Map.Entry<String, ClassBreakdown> classEntry : classes.entrySet()) {
					String className = classEntry.getKey();
					ClassBreakdown breakdown = classEntry.getValue();
					heartbeat.setItem(className);
					classProgress++;
					writeLine(out, "[ApkSpy] smali merge progress " + classProgress + "/" + classes.size()
							+ ": " + className);
					Path generatedClassPath = generatedIndex.get(className);

					if (generatedClassPath == null) {
						writeLine(out, "[ApkSpy] Could not locate generated smali for " + className);
						skippedClasses++;
						continue;
					}

					Path originalClassPath = originalIndex.get(className);
					if (originalClassPath != null) {
						LOG.info("Merging smali for class: {}", className);
						writeLine(out, "[ApkSpy] merging class family for " + className
								+ " using generated " + generatedClassPath.toAbsolutePath()
								+ " and original " + originalClassPath.toAbsolutePath());

						String modifiedContent = Files.readString(generatedClassPath, StandardCharsets.UTF_8);
						String originalContent = Files.readString(originalClassPath, StandardCharsets.UTF_8);
						SmaliBreakdown modifiedSmali = SmaliBreakdown.breakdown(modifiedContent);
						List<SmaliMethod> methods = modifiedSmali.getChangedMethods(breakdown);

						writeLine(out, "[ApkSpy] Merging " + methods.size() + " method(s) for " + className);
						StringBuilder builder = new StringBuilder(originalContent);
						for (SmaliMethod method : methods) {
							heartbeat.setItem(className + " :: " + method.getContent().split("\n")[0]);
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
						mergedClasses++;

						for (Map.Entry<String, Path> generatedClassEntry : generatedIndex.entrySet()) {
							String generatedClassName = generatedClassEntry.getKey();
							if (!isSyntheticNestedClass(className, generatedClassName)) {
								continue;
							}

							Path sourcePath = generatedClassEntry.getValue();
							Path destinationPath = prepareMergeDestination(sourcePath, generatedToOriginalRoots, out);
							if (destinationPath == null) {
								writeLine(out, "[ApkSpy] Skipping nested class " + generatedClassName
										+ ": no generated root found");
								continue;
							}
							if (originalIndex.containsKey(generatedClassName)) {
								writeLine(out, "[ApkSpy] Skipping nested class overwrite due to name collision: "
										+ generatedClassName);
								continue;
							}
							Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
							writeLine(out, "[ApkSpy] Copied nested class " + generatedClassName + " -> "
									+ destinationPath.toAbsolutePath());
							originalIndex.put(generatedClassName, destinationPath);
						}
						continue;
					}

					writeLine(out, "[ApkSpy] Copying added class family: " + className);
					for (Map.Entry<String, Path> generatedClassEntry : generatedIndex.entrySet()) {
						String generatedClassName = generatedClassEntry.getKey();
						if (!generatedClassName.equals(className) && !generatedClassName.startsWith(className + "$")) {
							continue;
						}

						Path sourcePath = generatedClassEntry.getValue();
						Path destinationPath = prepareMergeDestination(sourcePath, generatedToOriginalRoots, out);
						if (destinationPath == null) {
							writeLine(out, "[ApkSpy] Skipping added class copy for " + generatedClassName
									+ ": no generated root found");
							continue;
						}
						Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
						writeLine(out, "[ApkSpy] Copied added class " + generatedClassName + " -> "
								+ destinationPath.toAbsolutePath());
						originalIndex.put(generatedClassName, destinationPath);
						copiedClasses++;
					}
				}
				writeLine(out, "[ApkSpy] after smali merge: mergedClasses=" + mergedClasses
						+ ", copiedClasses=" + copiedClasses + ", skippedClasses=" + skippedClasses);

				mergeUnknownFiles(generatedUnknown, Paths.get("smali", "original"), originalUnknown, out, heartbeat);
				writeLine(out, "[ApkSpy] after unknown files merge/copy");

				Set<String> deletions = ChangeCache.getInstance().getClassDeletions();
				heartbeat.setStage("apply deletions");
				heartbeat.setItem("(class deletions)");
				writeLine(out, "[ApkSpy] applying class deletions: count=" + deletions.size());
				for (String deletion : deletions) {
					heartbeat.setItem(deletion);
					boolean deleted = false;
					for (Path path : destinationFolders) {
						Path candidate = Paths.get(path.toAbsolutePath().toString(), deletion);
						if (Files.deleteIfExists(candidate)) {
							writeLine(out, "[ApkSpy] Deleted class file " + candidate.toAbsolutePath());
							deleted = true;
							break;
						}
					}
					if (!deleted) {
						writeLine(out, "[ApkSpy] Deletion target not found: " + deletion);
					}
				}
			} catch (IOException throwable) {
				try {
					writeException(out, "post-process pipeline", throwable);
				} catch (IOException logFailure) {
					throwable.addSuppressed(logFailure);
				}
				throw throwable;
			} catch (RuntimeException | Error throwable) {
				try {
					writeException(out, "post-process pipeline", throwable);
				} catch (IOException logFailure) {
					throwable.addSuppressed(logFailure);
				}
				throw throwable;
			}
		}

		writeLine(out, "[ApkSpy] before rebuild: smali/original using strategy " + rebuildStrategy.getId());
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
