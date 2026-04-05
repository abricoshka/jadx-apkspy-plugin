package jadx.plugins.apkspy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import jadx.plugins.apkspy.model.ClassBreakdown;
import jadx.plugins.apkspy.model.CompileRenameRegistry;

final class CompileSymbolPlanner {

	private CompileSymbolPlanner() {
	}

	static CompileSymbolPlan plan(Map<String, ClassBreakdown> classes, Set<String> availableClassNames,
			CompileRenameRegistry renameRegistry) {
		Set<String> compileTargets = classes.keySet().stream()
				.map(className -> className.replace('.', '/'))
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> sourcePackages = new LinkedHashSet<>();
		Set<String> constructorBridgeOwners = new LinkedHashSet<>();

		for (Map.Entry<String, ClassBreakdown> classEntry : classes.entrySet()) {
			String className = classEntry.getKey();
			String packageName = getPackageName(className);
			if (!packageName.isEmpty()) {
				sourcePackages.add(packageName.replace('.', '/'));
			}

			CompilationUnit compilationUnit;
			try {
				compilationUnit = StaticJavaParser.parse(classEntry.getValue().toString());
			} catch (RuntimeException ignore) {
				continue;
			}

			Map<String, String> imports = compilationUnit.getImports().stream()
					.filter(importDeclaration -> !importDeclaration.isAsterisk())
					.collect(java.util.stream.Collectors.toMap(
							importDeclaration -> importDeclaration.getName().getIdentifier(),
							ImportDeclaration::getNameAsString,
							(left, right) -> left,
							LinkedHashMap::new));

			for (ClassOrInterfaceDeclaration declaration : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
				for (var extendedType : declaration.getExtendedTypes()) {
					String ownerInternalName = resolveTypeInternalName(packageName, imports, extendedType.asString(),
							availableClassNames, renameRegistry);
					if (ownerInternalName != null && !isCompileTargetFamily(ownerInternalName, compileTargets)) {
						constructorBridgeOwners.add(ownerInternalName);
					}
				}
			}

			for (ObjectCreationExpr creationExpr : compilationUnit.findAll(ObjectCreationExpr.class)) {
				String ownerInternalName = resolveTypeInternalName(packageName, imports, creationExpr.getType().asString(),
						availableClassNames, renameRegistry);
				if (ownerInternalName != null && !isCompileTargetFamily(ownerInternalName, compileTargets)) {
					constructorBridgeOwners.add(ownerInternalName);
				}
			}
		}

		Set<String> packageClassClashes = sourcePackages.stream()
				.filter(packageInternalName -> availableClassNames.contains(packageInternalName)
						&& !isCompileTargetFamily(packageInternalName, compileTargets))
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

		return new CompileSymbolPlan(packageClassClashes, constructorBridgeOwners);
	}

	private static boolean isCompileTargetFamily(String ownerInternalName, Set<String> compileTargets) {
		for (String compileTarget : compileTargets) {
			if (ownerInternalName.equals(compileTarget) || ownerInternalName.startsWith(compileTarget + "$")) {
				return true;
			}
		}
		return false;
	}

	private static String resolveTypeInternalName(String packageName, Map<String, String> imports, String rawTypeName,
			Set<String> availableClassNames, CompileRenameRegistry renameRegistry) {
		String typeName = normalizeTypeName(rawTypeName);
		if (typeName.isEmpty()) {
			return null;
		}

		Set<String> candidates = new LinkedHashSet<>();
		for (String ownerName : buildOwnerCandidates(packageName, imports, typeName)) {
			String renameResolved = renameRegistry.findRawInternalNameByAliasOrRawFullName(ownerName);
			if (renameResolved != null) {
				candidates.add(renameResolved);
			}
			for (String candidate : toInternalNameCandidates(ownerName)) {
				if (availableClassNames.contains(candidate)) {
					candidates.add(candidate);
				}
			}
		}
		return candidates.size() == 1 ? candidates.iterator().next() : null;
	}

	private static String normalizeTypeName(String rawTypeName) {
		if (rawTypeName == null) {
			return "";
		}
		String typeName = rawTypeName.trim();
		int genericStart = typeName.indexOf('<');
		if (genericStart >= 0) {
			typeName = typeName.substring(0, genericStart);
		}
		typeName = typeName.replace("...", "").replace("[]", "").trim();
		if (typeName.startsWith("? extends ")) {
			typeName = typeName.substring("? extends ".length()).trim();
		}
		if (typeName.startsWith("? super ")) {
			typeName = typeName.substring("? super ".length()).trim();
		}
		switch (typeName) {
			case "boolean":
			case "byte":
			case "char":
			case "short":
			case "int":
			case "long":
			case "float":
			case "double":
			case "void":
				return "";
			default:
				return typeName;
		}
	}

	private static List<String> buildOwnerCandidates(String packageName, Map<String, String> imports, String ownerExpression) {
		List<String> candidates = new ArrayList<>();
		if (ownerExpression == null || ownerExpression.isEmpty()) {
			return candidates;
		}

		String firstSegment = ownerExpression.contains(".")
				? ownerExpression.substring(0, ownerExpression.indexOf('.'))
				: ownerExpression;
		if (imports.containsKey(firstSegment)) {
			candidates.add(imports.get(firstSegment) + ownerExpression.substring(firstSegment.length()));
		}
		candidates.add(ownerExpression);
		if (!packageName.isEmpty() && ownerExpression.indexOf('.') < 0) {
			candidates.add(packageName + "." + ownerExpression);
		}
		return candidates;
	}

	private static List<String> toInternalNameCandidates(String ownerName) {
		String[] parts = ownerName.split("\\.");
		List<String> candidates = new ArrayList<>();
		if (parts.length == 1) {
			candidates.add(parts[0]);
			return candidates;
		}
		for (int packagePartCount = 0; packagePartCount < parts.length; packagePartCount++) {
			StringBuilder builder = new StringBuilder();
			if (packagePartCount > 0) {
				builder.append(String.join("/", java.util.Arrays.copyOfRange(parts, 0, packagePartCount)));
			}
			if (packagePartCount < parts.length) {
				if (builder.length() > 0) {
					builder.append('/');
				}
				builder.append(String.join("$", java.util.Arrays.copyOfRange(parts, packagePartCount, parts.length)));
			}
			candidates.add(builder.toString());
		}
		candidates.add(ownerName.replace('.', '/'));
		return candidates;
	}

	private static String getPackageName(String className) {
		return className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
	}

	static final class CompileSymbolPlan {
		private final Set<String> packageClassClashes;
		private final Set<String> constructorBridgeOwners;

		CompileSymbolPlan(Set<String> packageClassClashes, Set<String> constructorBridgeOwners) {
			this.packageClassClashes = packageClassClashes;
			this.constructorBridgeOwners = constructorBridgeOwners;
		}

		Set<String> getPackageClassClashes() {
			return Collections.unmodifiableSet(packageClassClashes);
		}

		Set<String> getConstructorBridgeOwners() {
			return Collections.unmodifiableSet(constructorBridgeOwners);
		}

		boolean hasPackageClassClash(String internalName) {
			return packageClassClashes.contains(internalName);
		}

		boolean needsConstructorBridge(String internalName) {
			return constructorBridgeOwners.contains(internalName);
		}
	}
}
