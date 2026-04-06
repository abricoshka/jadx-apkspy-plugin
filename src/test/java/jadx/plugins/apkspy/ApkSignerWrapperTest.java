package jadx.plugins.apkspy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.plugins.apkspy.ApkSpyOptions.SigningConfig;
import jadx.plugins.apkspy.ApkSpyOptions.SigningMode;
import jadx.plugins.apkspy.ApkSpyOptions.SigningScheme;
import jadx.plugins.apkspy.utils.Util;

class ApkSignerWrapperTest {

	@TempDir
	Path tempDir;

	@Test
	void resolveApkSignerUsesLatestBuildToolsVersion() throws Exception {
		Path buildToolsDir = tempDir.resolve("build-tools");
		Path olderSigner = createApkSigner(buildToolsDir.resolve("33.0.2"));
		Path newerSigner = createApkSigner(buildToolsDir.resolve("35.0.0"));

		Path resolved = ApkSignerWrapper.resolveApkSigner(tempDir.toString());

		assertThat(resolved).isEqualTo(newerSigner);
		assertThat(resolved).isNotEqualTo(olderSigner);
	}

	@Test
	void buildApkSignerCommandUsesDebugDefaultsAndRedactsPasswords() {
		SigningConfig config = new SigningConfig(SigningMode.DEBUG, "", "", SigningScheme.V1_V2_V3, "CERT", "", "");
		List<String> command = ApkSignerWrapper.buildApkSignerCommand(
				Path.of("tools", Util.isWindows() ? "apksigner.bat" : "apksigner"),
				Path.of("app-unsigned.apk"),
				Path.of("app-signed.apk"),
				config);

		assertThat(command).contains("sign", "--ks", "--ks-key-alias", ApkSignerWrapper.DEBUG_KEY_ALIAS,
				"--ks-pass", "pass:android", "--key-pass", "pass:android", "--out");
		assertThat(command).containsSequence("--v4-signing-enabled", "false");
		assertThat(String.join(" ", command)).doesNotContain("jarsigner");
		assertThat(ApkSignerWrapper.redactSensitiveArgs(command))
				.contains("pass:<redacted>")
				.doesNotContain("pass:android");
	}

	@Test
	void buildApkSignerCommandUsesStorePasswordWhenKeyPasswordMissing() {
		SigningConfig config = new SigningConfig(SigningMode.CUSTOM, "C:\\keys\\release.jks", "release",
				SigningScheme.V1_V2, "MYCERT", "storePass", "");
		List<String> command = ApkSignerWrapper.buildApkSignerCommand(
				Path.of("tools", Util.isWindows() ? "apksigner.bat" : "apksigner"),
				Path.of("input.apk"),
				Path.of("output.apk"),
				config);

		assertThat(command).containsSequence("--ks-pass", "pass:storePass");
		assertThat(command).containsSequence("--key-pass", "pass:storePass");
		assertThat(command).containsSequence("--v1-signer-name", "MYCERT");
		assertThat(command).containsSequence("--v3-signing-enabled", "false");
	}

	@Test
	void buildApkSignerCommandSupportsV1AndV3OnlyScheme() {
		SigningConfig config = new SigningConfig(SigningMode.CUSTOM, "C:\\keys\\release.jks", "release",
				SigningScheme.V1_V3, "CERT", "storePass", "keyPass");
		List<String> command = ApkSignerWrapper.buildApkSignerCommand(
				Path.of("tools", Util.isWindows() ? "apksigner.bat" : "apksigner"),
				Path.of("input.apk"),
				Path.of("output.apk"),
				config);

		assertThat(command).containsSequence("--v1-signing-enabled", "true");
		assertThat(command).containsSequence("--v2-signing-enabled", "false");
		assertThat(command).containsSequence("--v3-signing-enabled", "true");
		assertThat(command).containsSequence("--v4-signing-enabled", "false");
	}

	private Path createApkSigner(Path buildToolsVersionDir) throws Exception {
		Files.createDirectories(buildToolsVersionDir);
		Path signer = buildToolsVersionDir.resolve(Util.isWindows() ? "apksigner.bat" : "apksigner");
		Files.writeString(signer, "@echo off");
		return signer;
	}
}
