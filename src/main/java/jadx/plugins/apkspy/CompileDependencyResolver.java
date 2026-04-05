package jadx.plugins.apkspy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jadx.plugins.apkspy.model.ClassBreakdown;

final class CompileDependencyResolver {

	private CompileDependencyResolver() {
	}

	static DependencySelection resolve(Map<String, ClassBreakdown> classes) {
		StringBuilder combinedSources = new StringBuilder();
		for (ClassBreakdown breakdown : classes.values()) {
			combinedSources.append(breakdown.toString()).append('\n');
		}
		String source = combinedSources.toString();

		boolean usesAndroidX = source.contains("androidx.");
		boolean usesSupport = source.contains("android.support.");

		Set<String> dependencies = new LinkedHashSet<>();
		if (usesAndroidX || source.contains("androidx.appcompat.")) {
			dependencies.add("compileOnly 'androidx.appcompat:appcompat:1.7.1'");
		}
		if (usesAndroidX || source.contains("androidx.core.")) {
			dependencies.add("compileOnly 'androidx.core:core:1.16.0'");
		}
		if (source.contains("androidx.activity.")) {
			dependencies.add("compileOnly 'androidx.activity:activity:1.10.1'");
		}
		if (source.contains("androidx.fragment.")) {
			dependencies.add("compileOnly 'androidx.fragment:fragment:1.8.9'");
		}
		if (source.contains("androidx.lifecycle.")) {
			dependencies.add("compileOnly 'androidx.lifecycle:lifecycle-runtime:2.9.0'");
		}
		if (source.contains("androidx.recyclerview.")) {
			dependencies.add("compileOnly 'androidx.recyclerview:recyclerview:1.4.0'");
		}
		if (source.contains("androidx.preference.")) {
			dependencies.add("compileOnly 'androidx.preference:preference:1.2.1'");
		}
		if (source.contains("androidx.constraintlayout.")) {
			dependencies.add("compileOnly 'androidx.constraintlayout:constraintlayout:2.2.1'");
		}
		if (source.contains("androidx.multidex.")) {
			dependencies.add("compileOnly 'androidx.multidex:multidex:2.0.1'");
		}
		if (source.contains("com.google.android.material.")) {
			dependencies.add("compileOnly 'com.google.android.material:material:1.12.0'");
		}

		if (usesSupport || source.contains("android.support.v4.")) {
			dependencies.add("compileOnly 'com.android.support:support-v4:28.0.0'");
		}
		if (usesSupport || source.contains("android.support.v7.")) {
			dependencies.add("compileOnly 'com.android.support:appcompat-v7:28.0.0'");
		}
		if (source.contains("android.support.design.")) {
			dependencies.add("compileOnly 'com.android.support:design:28.0.0'");
		}
		if (source.contains("android.support.v7.widget.RecyclerView")) {
			dependencies.add("compileOnly 'com.android.support:recyclerview-v7:28.0.0'");
		}
		if (source.contains("android.support.v7.widget.CardView")) {
			dependencies.add("compileOnly 'com.android.support:cardview-v7:28.0.0'");
		}
		if (source.contains("android.support.multidex.")) {
			dependencies.add("compileOnly 'com.android.support:multidex:1.0.3'");
		}

		List<String> profiles = new ArrayList<>();
		if (usesAndroidX) {
			profiles.add("androidx");
		}
		if (usesSupport) {
			profiles.add("support");
		}
		if (profiles.isEmpty()) {
			profiles.add("stub-only");
		}

		String renderedDependencies = dependencies.isEmpty()
				? "    // no extra compile-only compat dependencies detected"
				: "    " + String.join("\n    ", dependencies);
		return new DependencySelection(renderedDependencies, String.join("+", profiles), usesAndroidX, usesSupport);
	}

	static final class DependencySelection {
		private final String renderedDependencies;
		private final String profileSummary;
		private final boolean usesAndroidX;
		private final boolean usesSupport;

		DependencySelection(String renderedDependencies, String profileSummary, boolean usesAndroidX, boolean usesSupport) {
			this.renderedDependencies = renderedDependencies;
			this.profileSummary = profileSummary;
			this.usesAndroidX = usesAndroidX;
			this.usesSupport = usesSupport;
		}

		String getRenderedDependencies() {
			return renderedDependencies;
		}

		String getProfileSummary() {
			return profileSummary;
		}

		boolean usesAndroidX() {
			return usesAndroidX;
		}

		boolean usesSupport() {
			return usesSupport;
		}
	}
}
