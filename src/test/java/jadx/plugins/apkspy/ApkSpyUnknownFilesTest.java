package jadx.plugins.apkspy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApkSpyUnknownFilesTest {

	@TempDir
	Path tempDir;

	@Test
	void mergeUnknownFilesOverlaysGeneratedIntoOriginalAndMergesApktoolYml() throws Exception {
		Path generatedRoot = tempDir.resolve("generated");
		Path originalRoot = tempDir.resolve("original");

		Path generatedUnknown = generatedRoot.resolve(Path.of("unknown", "META-INF", "generated.bin"));
		Files.createDirectories(generatedUnknown.getParent());
		Files.writeString(generatedUnknown, "generated", StandardCharsets.UTF_8);
		Files.writeString(generatedRoot.resolve("apktool.yml"),
				"""
						version: 2.11.1
						unknownFiles:
						  META-INF/generated.bin: '8'
						""",
				StandardCharsets.UTF_8);

		Path originalUnknown = originalRoot.resolve(Path.of("unknown", "META-INF", "original.bin"));
		Files.createDirectories(originalUnknown.getParent());
		Files.writeString(originalUnknown, "original", StandardCharsets.UTF_8);
		Files.writeString(originalRoot.resolve("apktool.yml"),
				"""
						version: 2.11.1
						unknownFiles:
						  META-INF/original.bin: '8'
						""",
				StandardCharsets.UTF_8);

		ApkSpy.UnknownFilesSnapshot generatedSnapshot = ApkSpy.collectUnknownFiles(generatedRoot);
		ApkSpy.UnknownFilesSnapshot originalSnapshot = ApkSpy.collectUnknownFiles(originalRoot);
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		ApkSpy.mergeUnknownFiles(generatedSnapshot, originalRoot, originalSnapshot, out);

		assertThat(Files.readString(originalRoot.resolve(Path.of("unknown", "META-INF", "generated.bin")),
				StandardCharsets.UTF_8)).isEqualTo("generated");
		assertThat(Files.readString(originalRoot.resolve(Path.of("unknown", "META-INF", "original.bin")),
				StandardCharsets.UTF_8)).isEqualTo("original");

		Map<String, String> mergedMappings = ApkSpy.parseUnknownFilesSection(
				Files.readString(originalRoot.resolve("apktool.yml"), StandardCharsets.UTF_8));
		assertThat(mergedMappings).containsEntry("META-INF/generated.bin", "'8'")
				.containsEntry("META-INF/original.bin", "'8'");
		assertThat(out.toString(StandardCharsets.UTF_8))
				.contains("Copied unknown file META-INF/generated.bin")
				.contains("Preserving original-only unknown files")
				.contains("unknown files merge summary: copied=1, skipped=0");
	}

	@Test
	void mergeUnknownFilesSectionAddsUnknownFilesSectionWhenMissing() {
		String merged = ApkSpy.mergeUnknownFilesSection(
				"version: 2.11.1\nsdkInfo:\n  minSdkVersion: 21\n",
				Map.of("META-INF/generated.bin", "'8'"));

		assertThat(ApkSpy.parseUnknownFilesSection(merged))
				.containsEntry("META-INF/generated.bin", "'8'");
	}

	@Test
	void selectsSmaliOnlyNoResRebuildStrategy() {
		ApkSpy.RebuildStrategy strategy = ApkSpy.selectRebuildStrategy(Collections.emptyMap());

		assertThat(strategy).isEqualTo(ApkSpy.RebuildStrategy.SMALI_ONLY_NO_RES);
		assertThat(strategy.decodeResources()).isFalse();
		assertThat(strategy.getId()).isEqualTo("smali-only-no-res");
	}

	@Test
	void skipsMissingOriginalRootsAndCreatesThemOnCopy() throws Exception {
		Path generatedSmali = tempDir.resolve(Path.of("smali", "generated", "smali"));
		Path generatedSmali2 = tempDir.resolve(Path.of("smali", "generated", "smali_classes2"));
		Path generatedSmali3 = tempDir.resolve(Path.of("smali", "generated", "smali_classes3"));
		Path originalSmali = tempDir.resolve(Path.of("smali", "original", "smali"));
		Files.createDirectories(generatedSmali);
		Files.createDirectories(generatedSmali2);
		Files.createDirectories(generatedSmali3);
		Files.createDirectories(originalSmali);

		Path originalClass = originalSmali.resolve(Path.of("com", "example", "Main.smali"));
		Files.createDirectories(originalClass.getParent());
		Files.writeString(originalClass, minimalSmali("com/example/Main"), StandardCharsets.UTF_8);

		Path generatedClass2 = generatedSmali2.resolve(Path.of("com", "example", "Feature.smali"));
		Files.createDirectories(generatedClass2.getParent());
		Files.writeString(generatedClass2, minimalSmali("com/example/Feature"), StandardCharsets.UTF_8);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Map<Path, Path> rootMap = ApkSpy.mapGeneratedToOriginalRoots(
				List.of(generatedSmali, generatedSmali2, generatedSmali3),
				out);

		try (ApkSpy.PostProcessHeartbeat heartbeat = ApkSpy.PostProcessHeartbeat.start(out)) {
			Map<String, Path> indexed = ApkSpy.indexSmaliFiles(new java.util.ArrayList<>(rootMap.values()), "original", out,
					heartbeat);
			assertThat(indexed).containsKey("com.example.Main").doesNotContainKey("com.example.Feature");
		}

		Path destination = ApkSpy.prepareMergeDestination(generatedClass2, rootMap, out);
		Files.copy(generatedClass2, destination, StandardCopyOption.REPLACE_EXISTING);

		assertThat(Files.isDirectory(tempDir.resolve(Path.of("smali", "original", "smali_classes2")))).isTrue();
		assertThat(Files.exists(destination)).isTrue();
		assertThat(out.toString(StandardCharsets.UTF_8))
				.contains("original root exists")
				.contains("original root missing")
				.contains("skip missing original root")
				.contains("created missing root for merge output");
	}

	private String minimalSmali(String className) {
		return """
				.class public L%s;
				.super Ljava/lang/Object;

				.method public constructor <init>()V
				    .locals 0
				    return-void
				.end method
				""".formatted(className);
	}
}
