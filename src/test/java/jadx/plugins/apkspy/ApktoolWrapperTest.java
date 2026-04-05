package jadx.plugins.apkspy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ApktoolWrapperTest {

	@Test
	void buildDecodeCommandUsesWindowsStylePathAndNoEmptyArgs() {
		List<String> command = ApktoolWrapper.buildApktoolDecodeCommand(
				Path.of("C:\\Users\\Mvideo\\Downloads\\Camera.apk"),
				"C:\\tools\\apktool_3.0.1.jar",
				"C:\\Program Files\\Android\\Android Studio\\jbr",
				"original",
				true);

		assertThat(command).doesNotContain("", " ");
		assertThat(command).contains("decode");
		assertThat(command).contains("smali\\original");
		assertThat(command).contains("C:\\Users\\Mvideo\\Downloads\\Camera.apk");
		assertThat(command).doesNotContain("-r");
	}

	@Test
	void buildDecodeCommandAddsResourcesFlagOnlyWhenNeeded() {
		List<String> command = ApktoolWrapper.buildApktoolDecodeCommand(
				Path.of("generated.apk"),
				"apktool.jar",
				"",
				"generated",
				false);

		assertThat(command).contains("-r");
		assertThat(command).doesNotContain("", " ");
		assertThat(command.get(0)).isEqualTo("java");
	}
}
