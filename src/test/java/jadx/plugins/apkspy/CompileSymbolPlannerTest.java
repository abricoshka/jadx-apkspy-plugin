package jadx.plugins.apkspy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jadx.plugins.apkspy.model.ClassBreakdown;
import jadx.plugins.apkspy.model.CompileRenameRegistry;

class CompileSymbolPlannerTest {

	@Test
	void detectsPackageClassClashAndConstructorBridgeRequests() {
		ClassBreakdown breakdown = ClassBreakdown.breakdown(
				"com.roblox.client.t.e",
				"e",
				"""
						package com.roblox.client.t;

						import com.roblox.client.u;

						public class e extends a {
						    public e() {
						        super("ctx");
						    }

						    public Object make() {
						        return new u();
						    }
						}
						""");
		Set<String> availableClassNames = new LinkedHashSet<>();
		availableClassNames.add("com/roblox/client/t");
		availableClassNames.add("com/roblox/client/t/a");
		availableClassNames.add("com/roblox/client/u");

		CompileRenameRegistry renameRegistry = new CompileRenameRegistry();
		renameRegistry.getOrCreate("com/roblox/client/t/a", "com.roblox.client.t.a", "com.roblox.client.t.a");
		renameRegistry.getOrCreate("com/roblox/client/u", "com.roblox.client.u", "com.roblox.client.u");

		CompileSymbolPlanner.CompileSymbolPlan plan = CompileSymbolPlanner.plan(
				Map.of("com.roblox.client.t.e", breakdown),
				availableClassNames,
				renameRegistry);

		assertThat(plan.getPackageClassClashes()).containsExactly("com/roblox/client/t");
		assertThat(plan.getConstructorBridgeOwners()).contains("com/roblox/client/t/a", "com/roblox/client/u");
	}
}
