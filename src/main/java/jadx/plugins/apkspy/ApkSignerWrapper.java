package jadx.plugins.apkspy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import jadx.plugins.apkspy.ApkSpyOptions.SigningConfig;
import jadx.plugins.apkspy.ApkSpyOptions.SigningScheme;
import jadx.plugins.apkspy.utils.Util;

public final class ApkSignerWrapper {
	static final String DEBUG_KEY_ALIAS = "androiddebugkey";
	static final String DEBUG_STORE_PASSWORD = "android";
	static final String DEBUG_KEY_PASSWORD = "android";
	static final String DEFAULT_V1_SIGNER_NAME = "CERT";

	private ApkSignerWrapper() {
	}

	public static String validateSigningConfig(String sdkPath, SigningConfig config) {
		if (resolveApkSigner(sdkPath) == null) {
			return "Could not find apksigner in Android SDK build-tools.";
		}
		SigningConfig effectiveConfig = effectiveConfig(config);
		if (effectiveConfig.isDebugMode()) {
			Path debugKeystore = resolveDefaultDebugKeystore();
			if (!Files.isRegularFile(debugKeystore)) {
				return "Debug keystore not found at " + debugKeystore
						+ ". Open Signing options to choose a custom keystore or create the standard debug keystore.";
			}
			return null;
		}
		if (effectiveConfig.getKeystorePath().isBlank()) {
			return "Please choose a keystore in Signing options.";
		}
		Path keystorePath = Path.of(effectiveConfig.getKeystorePath());
		if (!Files.isRegularFile(keystorePath)) {
			return "Keystore file not found: " + keystorePath;
		}
		if (effectiveConfig.getKeyAlias().isBlank()) {
			return "Please enter a key alias in Signing options.";
		}
		if (effectiveConfig.getStorePassword().isBlank()) {
			return "Please enter the keystore password in Signing options.";
		}
		return null;
	}

	public static Path resolveDefaultDebugKeystore() {
		return Path.of(System.getProperty("user.home"), ".android", "debug.keystore");
	}

	static Path resolveApkSigner(String sdkPath) {
		if (sdkPath == null || sdkPath.isBlank()) {
			return null;
		}
		Path buildToolsDir = Path.of(sdkPath, "build-tools");
		if (!Files.isDirectory(buildToolsDir)) {
			return null;
		}
		try (var versions = Files.list(buildToolsDir)) {
			return versions
					.filter(Files::isDirectory)
					.sorted(Comparator.comparing(Path::getFileName,
							(left, right) -> compareVersionNames(right.toString(), left.toString())))
					.map(ApkSignerWrapper::resolveApkSignerExecutable)
					.filter(path -> path != null && Files.isRegularFile(path))
					.findFirst()
					.orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	private static Path resolveApkSignerExecutable(Path buildToolsVersionDir) {
		Path script = buildToolsVersionDir.resolve(Util.isWindows() ? "apksigner.bat" : "apksigner");
		if (Files.isRegularFile(script)) {
			return script;
		}
		return null;
	}

	private static int compareVersionNames(String left, String right) {
		int[] leftParts = versionParts(left);
		int[] rightParts = versionParts(right);
		int max = Math.max(leftParts.length, rightParts.length);
		for (int i = 0; i < max; i++) {
			int leftPart = i < leftParts.length ? leftParts[i] : 0;
			int rightPart = i < rightParts.length ? rightParts[i] : 0;
			if (leftPart != rightPart) {
				return Integer.compare(leftPart, rightPart);
			}
		}
		return left.compareTo(right);
	}

	private static int[] versionParts(String value) {
		return Arrays.stream(value.split("[^0-9]+"))
				.filter(part -> !part.isBlank())
				.mapToInt(Integer::parseInt)
				.toArray();
	}

	static List<String> buildApkSignerCommand(Path apksignerPath, Path inputApkPath, Path outputApkPath, SigningConfig config) {
		SigningConfig effectiveConfig = effectiveConfig(config);
		List<String> command = new ArrayList<>();
		command.add(apksignerPath.toAbsolutePath().toString());
		command.add("sign");
		command.add("--ks");
		command.add(resolveKeystorePath(effectiveConfig).toAbsolutePath().toString());
		command.add("--ks-key-alias");
		command.add(resolveKeyAlias(effectiveConfig));
		command.add("--ks-pass");
		command.add("pass:" + resolveStorePassword(effectiveConfig));
		command.add("--key-pass");
		command.add("pass:" + resolveKeyPassword(effectiveConfig));
		command.add("--v1-signing-enabled");
		command.add(Boolean.toString(effectiveConfig.getScheme().isV1Enabled()));
		command.add("--v2-signing-enabled");
		command.add(Boolean.toString(effectiveConfig.getScheme().isV2Enabled()));
		command.add("--v3-signing-enabled");
		command.add(Boolean.toString(effectiveConfig.getScheme().isV3Enabled()));
		command.add("--v4-signing-enabled");
		command.add("false");
		if (effectiveConfig.getScheme().isV1Enabled() && !effectiveConfig.getV1SignerName().isBlank()) {
			command.add("--v1-signer-name");
			command.add(effectiveConfig.getV1SignerName());
		}
		command.add("--out");
		command.add(outputApkPath.toAbsolutePath().toString());
		command.add(inputApkPath.toAbsolutePath().toString());
		return command;
	}

	static List<String> redactSensitiveArgs(List<String> command) {
		List<String> redacted = new ArrayList<>(command.size());
		for (String arg : command) {
			if (arg != null && arg.startsWith("pass:")) {
				redacted.add("pass:<redacted>");
			} else {
				redacted.add(arg);
			}
		}
		return redacted;
	}

	static String describeSigningConfig(SigningConfig config) {
		SigningConfig effectiveConfig = effectiveConfig(config);
		if (effectiveConfig.isDebugMode()) {
			return "debug/test key, scheme=" + effectiveConfig.getScheme().getLabel()
					+ ", keystore=" + resolveDefaultDebugKeystore().toAbsolutePath();
		}
		return "custom keystore, scheme=" + effectiveConfig.getScheme().getLabel()
				+ ", keystore=" + Path.of(effectiveConfig.getKeystorePath()).toAbsolutePath()
				+ ", alias=" + effectiveConfig.getKeyAlias();
	}

	static void sign(Path apkPath, String sdkPath, String jdkLocation, SigningConfig config, OutputStream out)
			throws IOException, InterruptedException {
		Path apksignerPath = resolveApkSigner(sdkPath);
		if (apksignerPath == null) {
			throw new IOException("Could not find apksigner in Android SDK build-tools.");
		}
		SigningConfig effectiveConfig = effectiveConfig(config);
		Path signedOutput = apkPath.resolveSibling(tempSignedFileName(apkPath));
		Files.deleteIfExists(signedOutput);

		ApkSpy.writeLine(out, "[ApkSpy] signing output with apksigner: " + describeSigningConfig(effectiveConfig));
		List<String> command = buildApkSignerCommand(apksignerPath, apkPath, signedOutput, effectiveConfig);
		ApkSpy.writeLine(out, "[ApkSpy] apksigner command: " + String.join(" ", redactSensitiveArgs(command)));

		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		if (System.getenv("PATH") != null) {
			builder.environment().put("PATH", System.getenv("PATH"));
		}
		if (System.getenv("Path") != null) {
			builder.environment().put("Path", System.getenv("Path"));
		}
		if (jdkLocation != null && !jdkLocation.isBlank()) {
			builder.environment().put("JAVA_HOME", jdkLocation);
		}

		Process process = builder.start();
		try (BufferedReader reader =
				new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				ApkSpy.writeLine(out, line);
			}
		}
		int exitCode = process.waitFor();
		ApkSpy.writeLine(out, "[ApkSpy] apksigner finished with exitCode=" + exitCode);
		if (exitCode != 0) {
			Files.deleteIfExists(idsigPath(signedOutput));
			Files.deleteIfExists(signedOutput);
			throw new IOException("apksigner exited with code " + exitCode);
		}
		Files.deleteIfExists(idsigPath(signedOutput));
		Files.move(signedOutput, apkPath, StandardCopyOption.REPLACE_EXISTING);
		Files.deleteIfExists(idsigPath(apkPath));
		ApkSpy.writeLine(out, "[ApkSpy] signed APK written to " + apkPath.toAbsolutePath());
	}

	private static String tempSignedFileName(Path apkPath) {
		String fileName = apkPath.getFileName().toString();
		if (fileName.endsWith(".apk")) {
			return fileName.substring(0, fileName.length() - 4) + "-signed.apk";
		}
		return fileName + "-signed.apk";
	}

	private static Path resolveKeystorePath(SigningConfig config) {
		if (config.isDebugMode()) {
			return resolveDefaultDebugKeystore();
		}
		return Path.of(config.getKeystorePath());
	}

	private static String resolveKeyAlias(SigningConfig config) {
		return config.isDebugMode() ? DEBUG_KEY_ALIAS : config.getKeyAlias();
	}

	private static String resolveStorePassword(SigningConfig config) {
		return config.isDebugMode() ? DEBUG_STORE_PASSWORD : config.getStorePassword();
	}

	private static String resolveKeyPassword(SigningConfig config) {
		if (config.isDebugMode()) {
			return DEBUG_KEY_PASSWORD;
		}
		return config.getKeyPassword().isBlank() ? config.getStorePassword() : config.getKeyPassword();
	}

	private static Path idsigPath(Path apkPath) {
		return apkPath.resolveSibling(apkPath.getFileName().toString() + ".idsig");
	}

	private static SigningConfig effectiveConfig(SigningConfig config) {
		return config == null
				? new SigningConfig(ApkSpyOptions.SigningMode.DEBUG, "", "", SigningScheme.V1_V2_V3,
						DEFAULT_V1_SIGNER_NAME, "", "")
				: config;
	}
}
