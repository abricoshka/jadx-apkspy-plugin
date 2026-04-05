package jadx.plugins.apkspy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

final class CompileSourceNormalizer {

	private static final Pattern R_REFERENCE_PATTERN = Pattern.compile("(?<![\\w$.])R\\.([A-Za-z_][\\w]*)\\.([A-Za-z_][\\w]*)");
	private static final Pattern SYNTHETIC_FIELD_PATTERN = Pattern.compile("f\\d+[A-Za-z_$]\\w*");

	private CompileSourceNormalizer() {
	}

	static NormalizedSource normalize(String className, String source) {
		try {
			CompilationUnit compilationUnit = StaticJavaParser.parse(source);
			LexicalPreservingPrinter.setup(compilationUnit);

			List<String> diagnostics = new ArrayList<>();
			Map<String, Set<String>> rReferences = collectRReferences(compilationUnit);

			int entrySetLoops = rewriteEntrySetForeachLoops(compilationUnit);
			if (entrySetLoops > 0) {
				diagnostics.add("rewrote " + entrySetLoops + " raw entrySet() foreach loop(s)");
			}

			int orientationSupers = injectOrientationEventListenerSuperCalls(compilationUnit);
			if (orientationSupers > 0) {
				diagnostics.add("inserted " + orientationSupers + " missing OrientationEventListener super(...) call(s)");
			}

			List<String> syntheticFields = injectSyntheticInnerFieldStubs(compilationUnit);
			if (!syntheticFields.isEmpty()) {
				diagnostics.add("added compile-time synthetic field stub(s): " + String.join(", ", syntheticFields));
			}

			return new NormalizedSource(LexicalPreservingPrinter.print(compilationUnit), rReferences, diagnostics);
		} catch (RuntimeException e) {
			List<String> diagnostics = new ArrayList<>();
			diagnostics.add("source normalizer fallback for " + className + ": " + e.getClass().getSimpleName());
			return new NormalizedSource(source, collectRReferences(source), diagnostics);
		}
	}

	static String renderRSource(String packageName, Map<String, Set<String>> rReferences) {
		StringBuilder builder = new StringBuilder();
		if (!packageName.isEmpty()) {
			builder.append("package ").append(packageName).append(";\n\n");
		}
		builder.append("public final class R {\n");
		builder.append("    private R() {\n");
		builder.append("    }\n");

		List<String> types = new ArrayList<>(rReferences.keySet());
		Collections.sort(types);
		for (String type : types) {
			builder.append("\n");
			builder.append("    public static final class ").append(type).append(" {\n");
			builder.append("        private ").append(type).append("() {\n");
			builder.append("        }\n");

			List<String> names = new ArrayList<>(rReferences.get(type));
			Collections.sort(names);
			for (String name : names) {
				builder.append("        public static final int ").append(name).append(" = 0;\n");
			}
			builder.append("    }\n");
		}
		builder.append("}\n");
		return builder.toString();
	}

	private static Map<String, Set<String>> collectRReferences(CompilationUnit compilationUnit) {
		Map<String, Set<String>> references = new LinkedHashMap<>();
		for (FieldAccessExpr fieldAccess : compilationUnit.findAll(FieldAccessExpr.class)) {
			if (!(fieldAccess.getScope() instanceof FieldAccessExpr)) {
				continue;
			}
			FieldAccessExpr innerAccess = (FieldAccessExpr) fieldAccess.getScope();
			if (!(innerAccess.getScope() instanceof NameExpr)
					|| !"R".equals(((NameExpr) innerAccess.getScope()).getNameAsString())) {
				continue;
			}
			references.computeIfAbsent(innerAccess.getNameAsString(), key -> new LinkedHashSet<>())
					.add(fieldAccess.getNameAsString());
		}
		return references;
	}

	private static Map<String, Set<String>> collectRReferences(String source) {
		Map<String, Set<String>> references = new LinkedHashMap<>();
		Matcher matcher = R_REFERENCE_PATTERN.matcher(source);
		while (matcher.find()) {
			references.computeIfAbsent(matcher.group(1), key -> new LinkedHashSet<>()).add(matcher.group(2));
		}
		return references;
	}

	private static int rewriteEntrySetForeachLoops(CompilationUnit compilationUnit) {
		int counter = 0;
		int entryIndex = 0;
		for (ForEachStmt loop : compilationUnit.findAll(ForEachStmt.class)) {
			if (!loop.getIterable().toString().contains(".entrySet()")) {
				continue;
			}
			VariableDeclarationExpr variableDeclaration = loop.getVariable();
			if (variableDeclaration.getVariables().size() != 1) {
				continue;
			}
			VariableDeclarator variable = variableDeclaration.getVariable(0);
			String typeName = variable.getType().asString();
			if (!typeName.endsWith("Entry")) {
				continue;
			}

			String originalVariableName = variable.getNameAsString();
			String tempVariableName = "apkSpyEntryObj" + entryIndex++;
			Type originalType = variable.getType().clone();
			variable.setType("Object");
			variable.setName(tempVariableName);

			Statement originalBody = loop.getBody().clone();
			BlockStmt block = new BlockStmt();
			block.addStatement(StaticJavaParser
					.parseStatement(originalType.asString() + " " + originalVariableName + " = (" + originalType.asString()
							+ ") " + tempVariableName + ";"));
			if (originalBody.isBlockStmt()) {
				for (Statement statement : originalBody.asBlockStmt().getStatements()) {
					block.addStatement(statement);
				}
			} else {
				block.addStatement(originalBody);
			}
			loop.setBody(block);
			counter++;
		}
		return counter;
	}

	private static int injectOrientationEventListenerSuperCalls(CompilationUnit compilationUnit) {
		int counter = 0;
		for (ClassOrInterfaceDeclaration declaration : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
			if (!extendsType(declaration, "OrientationEventListener")) {
				continue;
			}
			for (ConstructorDeclaration constructor : declaration.getConstructors()) {
				if (hasExplicitConstructorInvocation(constructor)) {
					continue;
				}
				String superCall = buildOrientationEventListenerSuperCall(constructor);
				if (superCall == null) {
					continue;
				}
				constructor.getBody().addStatement(0, StaticJavaParser.parseStatement(superCall));
				counter++;
			}
		}
		return counter;
	}

	private static boolean extendsType(ClassOrInterfaceDeclaration declaration, String simpleName) {
		return declaration.getExtendedTypes().stream()
				.map(NodeWithSimpleName::getNameAsString)
				.anyMatch(name -> name.equals(simpleName));
	}

	private static boolean hasExplicitConstructorInvocation(ConstructorDeclaration constructor) {
		return constructor.getBody().getStatements().stream().findFirst()
				.filter(ExplicitConstructorInvocationStmt.class::isInstance)
				.isPresent();
	}

	private static String buildOrientationEventListenerSuperCall(ConstructorDeclaration constructor) {
		if (constructor.getParameters().isEmpty()) {
			return null;
		}

		if (!looksLikeContextParameter(constructor.getParameter(0).getType(), constructor.getParameter(0).getNameAsString())) {
			return null;
		}

		StringBuilder builder = new StringBuilder("super(");
		builder.append(constructor.getParameter(0).getNameAsString());
		if (constructor.getParameters().size() > 1) {
			Type secondType = constructor.getParameter(1).getType();
			if (looksLikeIntParameter(secondType, constructor.getParameter(1).getNameAsString())) {
				builder.append(", ").append(constructor.getParameter(1).getNameAsString());
			}
		}
		builder.append(");");
		return builder.toString();
	}

	private static boolean looksLikeContextParameter(Type type, String name) {
		String typeName = type.asString();
		return typeName.equals("Context")
				|| typeName.endsWith(".Context")
				|| name.toLowerCase().contains("context");
	}

	private static boolean looksLikeIntParameter(Type type, String name) {
		String typeName = type.asString();
		return typeName.equals("int")
				|| typeName.equals("Integer")
				|| name.equals("i")
				|| name.equals("rate")
				|| name.equals("sensorRate");
	}

	private static List<String> injectSyntheticInnerFieldStubs(CompilationUnit compilationUnit) {
		Map<String, ClassOrInterfaceDeclaration> nestedTypes = compilationUnit.findAll(ClassOrInterfaceDeclaration.class).stream()
				.filter(type -> type.findAncestor(ClassOrInterfaceDeclaration.class).isPresent())
				.collect(Collectors.toMap(ClassOrInterfaceDeclaration::getNameAsString, type -> type, (left, right) -> left,
						LinkedHashMap::new));
		if (nestedTypes.isEmpty()) {
			return Collections.emptyList();
		}

		Map<ClassOrInterfaceDeclaration, Map<String, String>> pendingStubs = new LinkedHashMap<>();
		for (FieldAccessExpr fieldAccess : compilationUnit.findAll(FieldAccessExpr.class)) {
			if (!(fieldAccess.getScope() instanceof NameExpr)) {
				continue;
			}
			String fieldName = fieldAccess.getNameAsString();
			if (!SYNTHETIC_FIELD_PATTERN.matcher(fieldName).matches()) {
				continue;
			}

			String scopeName = ((NameExpr) fieldAccess.getScope()).getNameAsString();
			ClassOrInterfaceDeclaration owner = nestedTypes.get(scopeName);
			if (owner == null || hasField(owner, fieldName)) {
				continue;
			}

			String requestedType = isArrayAccessTarget(fieldAccess) ? "int[]" : "Object";
			pendingStubs.computeIfAbsent(owner, key -> new LinkedHashMap<>())
					.merge(fieldName, requestedType, CompileSourceNormalizer::pickMoreSpecificType);
		}

		if (pendingStubs.isEmpty()) {
			return Collections.emptyList();
		}

		List<String> addedFields = new ArrayList<>();
		for (Map.Entry<ClassOrInterfaceDeclaration, Map<String, String>> classEntry : pendingStubs.entrySet()) {
			ClassOrInterfaceDeclaration owner = classEntry.getKey();
			for (Map.Entry<String, String> fieldEntry : classEntry.getValue().entrySet()) {
				String declaration = renderSyntheticFieldStub(fieldEntry.getKey(), fieldEntry.getValue());
				owner.getMembers().add(0, StaticJavaParser.parseBodyDeclaration(declaration));
				addedFields.add(owner.getNameAsString() + "." + fieldEntry.getKey());
			}
		}
		return addedFields;
	}

	private static boolean hasField(ClassOrInterfaceDeclaration owner, String fieldName) {
		for (FieldDeclaration field : owner.getFields()) {
			for (VariableDeclarator variable : field.getVariables()) {
				if (fieldName.equals(variable.getNameAsString())) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isArrayAccessTarget(FieldAccessExpr fieldAccess) {
		return fieldAccess.getParentNode()
				.filter(parent -> parent instanceof ArrayAccessExpr && isSameNode(((ArrayAccessExpr) parent).getName(), fieldAccess))
				.isPresent();
	}

	private static boolean isSameNode(Expression left, FieldAccessExpr right) {
		return left == right || left.equals(right);
	}

	private static String pickMoreSpecificType(String left, String right) {
		if ("int[]".equals(left) || "int[]".equals(right)) {
			return "int[]";
		}
		return left;
	}

	private static String renderSyntheticFieldStub(String fieldName, String typeName) {
		if ("int[]".equals(typeName)) {
			return "static int[] " + fieldName + " = new int[0];";
		}
		return "static Object " + fieldName + " = null;";
	}

	static final class NormalizedSource {
		private final String source;
		private final Map<String, Set<String>> rReferences;
		private final List<String> diagnostics;

		NormalizedSource(String source, Map<String, Set<String>> rReferences, List<String> diagnostics) {
			this.source = source;
			this.rReferences = rReferences;
			this.diagnostics = diagnostics;
		}

		String getSource() {
			return source;
		}

		Map<String, Set<String>> getRReferences() {
			return rReferences;
		}

		List<String> getDiagnostics() {
			return diagnostics;
		}
	}
}
