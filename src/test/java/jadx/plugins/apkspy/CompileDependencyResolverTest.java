package jadx.plugins.apkspy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import jadx.plugins.apkspy.model.ClassBreakdown;

class CompileDependencyResolverTest {

	@Test
	void resolvesSupportDependenciesWithoutAndroidXRuntimeMix() {
		ClassBreakdown breakdown = ClassBreakdown.breakdown("test.CameraActivity", "CameraActivity",
				"package test;\n"
						+ "\n"
						+ "import android.support.v4.app.ActivityCompat;\n"
						+ "import android.support.v7.app.AppCompatActivity;\n"
						+ "\n"
						+ "public class CameraActivity extends AppCompatActivity {\n"
						+ "}\n");

		CompileDependencyResolver.DependencySelection selection = CompileDependencyResolver
				.resolve(Map.of("test.CameraActivity", breakdown));

		assertThat(selection.getProfileSummary()).isEqualTo("support");
		assertThat(selection.getRenderedDependencies()).contains("compileOnly 'com.android.support:appcompat-v7:28.0.0'");
		assertThat(selection.getRenderedDependencies()).contains("compileOnly 'com.android.support:support-v4:28.0.0'");
		assertThat(selection.getRenderedDependencies()).doesNotContain("implementation ");
		assertThat(selection.getRenderedDependencies()).doesNotContain("androidx.appcompat");
	}

	@Test
	void resolvesAndroidXDependenciesWithoutLegacySupportMix() {
		ClassBreakdown breakdown = ClassBreakdown.breakdown("test.Screen", "Screen",
				"package test;\n"
						+ "\n"
						+ "import androidx.fragment.app.Fragment;\n"
						+ "import androidx.lifecycle.LiveData;\n"
						+ "\n"
						+ "public class Screen extends Fragment {\n"
						+ "    LiveData<?> value;\n"
						+ "}\n");

		CompileDependencyResolver.DependencySelection selection = CompileDependencyResolver
				.resolve(Map.of("test.Screen", breakdown));

		assertThat(selection.getProfileSummary()).isEqualTo("androidx");
		assertThat(selection.getRenderedDependencies()).contains("compileOnly 'androidx.fragment:fragment:1.8.9'");
		assertThat(selection.getRenderedDependencies()).contains("compileOnly 'androidx.lifecycle:lifecycle-runtime:2.9.0'");
		assertThat(selection.getRenderedDependencies()).doesNotContain("com.android.support:");
	}
}
