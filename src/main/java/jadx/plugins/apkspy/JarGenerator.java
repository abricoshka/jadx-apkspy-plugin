package jadx.plugins.apkspy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.googlecode.dex2jar.tools.Dex2jarCmd;

import jadx.plugins.apkspy.model.ClassBreakdown;
import jadx.plugins.apkspy.model.ChangeCache;
import jadx.plugins.apkspy.model.CompileRenameRegistry;
import jadx.plugins.apkspy.utils.Util;

public class JarGenerator {

	private static final Logger LOG = LoggerFactory.getLogger(JarGenerator.class);
	private static final Pattern JADX_RENAMED_FIELD_PATTERN = Pattern.compile("f\\d+[A-Za-z_$]\\w*");
	private static final String CONSTRUCTOR_BRIDGE_DESC = "([Ljava/lang/Object;)V";

	private static boolean isExcludedClassEntry(String entryName, Set<String> excludedClasses) {
		if (!entryName.endsWith(".class")) {
			return false;
		}
		String className = entryName.substring(0, entryName.length() - ".class".length());
		for (String excludedClass : excludedClasses) {
			String internalName = excludedClass.replace('.', '/');
			if (className.equals(internalName) || className.startsWith(internalName + "$")) {
				return true;
			}
		}
		return false;
	}

	public static void generateStubJar(File apk, File output, OutputStream out, Map<String, ClassBreakdown> classes)
	        throws IOException, InterruptedException {
	
	    Util.attemptDelete(new File("decompiled-apk"));
	
	    ByteArrayOutputStream dex2jarErr = new ByteArrayOutputStream();
	    PrintStream oldErr = System.err;
	
	    try (PrintStream captureErr = new PrintStream(dex2jarErr, true, StandardCharsets.UTF_8)) {
	        System.setErr(captureErr);
	
	        Dex2jarCmd.main(
	                "-f",
	                "-nc",
	                "-o", output.getAbsolutePath(),
	                apk.getAbsolutePath()
	        );
	    } finally {
	        System.setErr(oldErr);
	    }
	
	    if (!output.exists()) {
	        String errText = dex2jarErr.toString(StandardCharsets.UTF_8);
	        if (errText.isBlank()) {
	            throw new IOException("dex2jar did not create stub jar: " + output.getAbsolutePath());
	        }
	        throw new IOException(
	                "dex2jar did not create stub jar: " + output.getAbsolutePath()
	                        + "\n--- dex2jar stderr ---\n"
	                        + errText
	        );
	    }
	
	    Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "apkSpy", "dex2jar-classes");
	    Util.attemptDelete(tmpDir.toFile());
	    Files.createDirectories(tmpDir);
	
	    JarFile jarFile = new JarFile(output);
		Set<String> availableClassNames = new HashSet<>();
		Enumeration<JarEntry> entries = jarFile.entries();
		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			String entryName = entry.getName();
			if (entryName.endsWith(".class")) {
				availableClassNames.add(entryName.substring(0, entryName.length() - ".class".length()));
			}
		}
		Map<String, List<RequestedFieldAlias>> requestedFieldAliases = collectRequestedFieldAliases(classes,
				availableClassNames);
		CompileRenameRegistry renameRegistry = ChangeCache.getInstance().getRenameRegistry();
		CompileSymbolPlanner.CompileSymbolPlan symbolPlan = CompileSymbolPlanner.plan(classes, availableClassNames,
				renameRegistry);
		for (String packageClash : symbolPlan.getPackageClassClashes()) {
			writeLine(out, "[ApkSpy] Excluding compile-time class due to package/class clash: "
					+ packageClash.replace('/', '.'));
		}
		for (String bridgeOwner : symbolPlan.getConstructorBridgeOwners()) {
			writeLine(out, "[ApkSpy] Planned compile-time constructor bridge for "
					+ bridgeOwner.replace('/', '.'));
		}

		entries = jarFile.entries();
		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();

			String entryName = entry.getName();
			if (isExcludedClassEntry(entryName, classes.keySet())
					|| (entryName.endsWith(".class")
							&& symbolPlan.hasPackageClassClash(entryName.substring(0, entryName.length() - ".class".length())))) {
				continue;
			}
			if (entryName.endsWith(".class")) {
				visitClass(jarFile, entry, tmpDir, requestedFieldAliases, renameRegistry, symbolPlan, out);
			} else if (!entry.isDirectory()) {
				InputStream is = jarFile.getInputStream(entry);
				Path path = tmpDir.resolve(entryName);
				path.toFile().getParentFile().mkdirs();
				FileUtils.copyInputStreamToFile(is, path.toFile());
			}
		}
		jarFile.close();

		output.delete();
		pack(tmpDir, output.toPath());

		Util.attemptDelete(tmpDir.toFile());
	}

	private static void writeLine(OutputStream out, String message) throws IOException {
		out.write(message.getBytes(StandardCharsets.UTF_8));
		out.write('\n');
	}

	static Map<String, List<RequestedFieldAlias>> collectRequestedFieldAliases(Map<String, ClassBreakdown> classes,
			Set<String> availableClassNames) {
		Map<String, Map<String, RequestedFieldAlias>> requestedAliases = new LinkedHashMap<>();
		Set<String> compileTargets = classes.keySet().stream()
				.map(className -> className.replace('.', '/'))
				.collect(Collectors.toSet());

		for (Map.Entry<String, ClassBreakdown> classEntry : classes.entrySet()) {
			String className = classEntry.getKey();
			CompilationUnit compilationUnit;
			try {
				compilationUnit = StaticJavaParser.parse(classEntry.getValue().toString());
			} catch (RuntimeException ignore) {
				continue;
			}

			String packageName = compilationUnit.getPackageDeclaration()
					.map(pkg -> pkg.getNameAsString())
					.orElseGet(() -> getPackageName(className));
			Map<String, String> imports = compilationUnit.getImports().stream()
					.filter(importDeclaration -> !importDeclaration.isAsterisk())
					.collect(Collectors.toMap(
							importDeclaration -> importDeclaration.getName().getIdentifier(),
							ImportDeclaration::getNameAsString,
							(left, right) -> left,
							LinkedHashMap::new));

			for (FieldAccessExpr fieldAccess : compilationUnit.findAll(FieldAccessExpr.class)) {
				String fieldName = fieldAccess.getNameAsString();
				if (!JADX_RENAMED_FIELD_PATTERN.matcher(fieldName).matches() || !isReadOnly(fieldAccess)) {
					continue;
				}

				String ownerInternalName = resolveOwnerInternalName(packageName, imports, fieldAccess.getScope().toString(),
						availableClassNames);
				if (ownerInternalName == null || isCompileTargetFamily(ownerInternalName, compileTargets)) {
					continue;
				}

				boolean arrayContext = fieldAccess.getParentNode()
						.filter(parent -> parent instanceof ArrayAccessExpr
								&& ((ArrayAccessExpr) parent).getName() == fieldAccess)
						.isPresent();
				requestedAliases.computeIfAbsent(ownerInternalName, key -> new LinkedHashMap<>())
						.compute(fieldName, (key, current) -> current == null
								? new RequestedFieldAlias(fieldName, arrayContext)
								: current.merge(arrayContext));
			}
		}

		return requestedAliases.entrySet().stream()
				.collect(Collectors.toMap(
						Map.Entry::getKey,
						entry -> new ArrayList<>(entry.getValue().values()),
						(left, right) -> left,
						LinkedHashMap::new));
	}

	private static boolean isReadOnly(FieldAccessExpr fieldAccess) {
		return fieldAccess.getParentNode()
				.map(parent -> {
					if (parent instanceof AssignExpr) {
						return ((AssignExpr) parent).getTarget() != fieldAccess;
					}
					if (parent instanceof UnaryExpr) {
						UnaryExpr.Operator operator = ((UnaryExpr) parent).getOperator();
						return operator != UnaryExpr.Operator.POSTFIX_DECREMENT
								&& operator != UnaryExpr.Operator.POSTFIX_INCREMENT
								&& operator != UnaryExpr.Operator.PREFIX_DECREMENT
								&& operator != UnaryExpr.Operator.PREFIX_INCREMENT;
					}
					return true;
				})
				.orElse(true);
	}

	private static boolean isCompileTargetFamily(String ownerInternalName, Set<String> compileTargets) {
		for (String compileTarget : compileTargets) {
			if (ownerInternalName.equals(compileTarget) || ownerInternalName.startsWith(compileTarget + "$")) {
				return true;
			}
		}
		return false;
	}

	private static String resolveOwnerInternalName(String packageName, Map<String, String> imports, String ownerExpression,
			Set<String> availableClassNames) {
		Set<String> candidates = new HashSet<>();
		for (String ownerName : buildOwnerCandidates(packageName, imports, ownerExpression)) {
			for (String candidate : toInternalNameCandidates(ownerName)) {
				if (availableClassNames.contains(candidate)) {
					candidates.add(candidate);
				}
			}
		}
		if (candidates.size() == 1) {
			return candidates.iterator().next();
		}
		return null;
	}

	private static List<String> buildOwnerCandidates(String packageName, Map<String, String> imports, String ownerExpression) {
		List<String> candidates = new ArrayList<>();
		if (ownerExpression == null || ownerExpression.isEmpty() || "this".equals(ownerExpression)
				|| "super".equals(ownerExpression)) {
			return candidates;
		}

		String firstSegment = ownerExpression.contains(".")
				? ownerExpression.substring(0, ownerExpression.indexOf('.'))
				: ownerExpression;
		if (imports.containsKey(firstSegment)) {
			candidates.add(imports.get(firstSegment) + ownerExpression.substring(firstSegment.length()));
		}
		candidates.add(ownerExpression);
		if (!packageName.isEmpty() && !ownerExpression.contains(packageName + ".")) {
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

	static List<String> applyRenameAliases(ClassNode classNode, CompileRenameRegistry.RenamedClassInfo renamedClassInfo) {
		if (renamedClassInfo == null) {
			return List.of();
		}
		List<String> addedAliases = new ArrayList<>();
		Set<String> existingFieldNames = classNode.fields.stream().map(field -> field.name).collect(Collectors.toSet());
		Map<String, FieldNode> fieldsByRawName = classNode.fields.stream()
				.collect(Collectors.toMap(field -> field.name, field -> field, (left, right) -> left, LinkedHashMap::new));

		for (Map.Entry<String, String> fieldAliasEntry : renamedClassInfo.getRawToAliasFieldNames().entrySet()) {
			String rawFieldName = fieldAliasEntry.getKey();
			String aliasFieldName = fieldAliasEntry.getValue();
			FieldNode rawField = fieldsByRawName.get(rawFieldName);
			if (rawField == null || existingFieldNames.contains(aliasFieldName)) {
				continue;
			}
			classNode.fields.add(new FieldNode(stripSyntheticAccess(rawField.access),
					aliasFieldName, rawField.desc, rawField.signature, null));
			existingFieldNames.add(aliasFieldName);
			addedAliases.add("field " + aliasFieldName + "->" + rawFieldName);
		}

		Set<String> existingMethodKeys = classNode.methods.stream()
				.map(method -> method.name + "#" + Type.getArgumentTypes(method.desc).length)
				.collect(Collectors.toSet());
		for (MethodNode methodNode : new ArrayList<>(classNode.methods)) {
			int argCount = Type.getArgumentTypes(methodNode.desc).length;
			String aliasName = renamedClassInfo.getMethodAliases()
					.get(new CompileRenameRegistry.RenamedMethodKey(methodNode.name, argCount));
			if (aliasName == null || existingMethodKeys.contains(aliasName + "#" + argCount)) {
				continue;
			}
			classNode.methods.add(new MethodNode(stripSyntheticAccess(methodNode.access),
					aliasName, methodNode.desc, methodNode.signature,
					methodNode.exceptions == null ? null : methodNode.exceptions.toArray(new String[0])));
			existingMethodKeys.add(aliasName + "#" + argCount);
			addedAliases.add("method " + aliasName + "->" + methodNode.name + "/" + argCount);
		}
		return addedAliases;
	}

	private static int stripSyntheticAccess(int access) {
		return access & ~Opcodes.ACC_SYNTHETIC;
	}

	static List<String> applyRequestedFieldAliases(ClassNode classNode, List<RequestedFieldAlias> requestedFieldAliases) {
		if (requestedFieldAliases == null || requestedFieldAliases.isEmpty()) {
			return List.of();
		}
		Set<String> existingFields = classNode.fields.stream().map(field -> field.name).collect(Collectors.toSet());
		List<String> addedFields = new ArrayList<>();

		for (RequestedFieldAlias requestedFieldAlias : requestedFieldAliases) {
			if (existingFields.contains(requestedFieldAlias.getName())) {
				continue;
			}
			String descriptor = chooseAliasDescriptor(classNode, requestedFieldAlias.isArrayContext());
			if (descriptor == null) {
				continue;
			}
			classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
					requestedFieldAlias.getName(), descriptor, null, null));
			existingFields.add(requestedFieldAlias.getName());
			addedFields.add(requestedFieldAlias.getName() + ":" + descriptor);
		}
		return addedFields;
	}

	static boolean applyRequestedConstructorBridge(ClassNode classNode, boolean requested) {
		if (!requested) {
			return false;
		}
		boolean hasConstructorBridge = classNode.methods.stream()
				.anyMatch(methodNode -> "<init>".equals(methodNode.name) && CONSTRUCTOR_BRIDGE_DESC.equals(methodNode.desc));
		if (hasConstructorBridge) {
			return false;
		}
		classNode.methods.add(0, new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_VARARGS, "<init>",
				CONSTRUCTOR_BRIDGE_DESC, null, null));
		return true;
	}

	private static String chooseAliasDescriptor(ClassNode classNode, boolean arrayContext) {
		List<FieldNode> staticFields = classNode.fields.stream()
				.filter(fieldNode -> (fieldNode.access & Opcodes.ACC_STATIC) != 0)
				.collect(Collectors.toList());
		if (staticFields.isEmpty()) {
			return null;
		}

		List<FieldNode> matchingFields = staticFields;
		if (arrayContext) {
			matchingFields = staticFields.stream()
					.filter(fieldNode -> fieldNode.desc.startsWith("["))
					.collect(Collectors.toList());
			if (matchingFields.size() == 1) {
				return matchingFields.get(0).desc;
			}
		}

		if (matchingFields.size() == 1) {
			return matchingFields.get(0).desc;
		}

		List<FieldNode> staticFinalFields = matchingFields.stream()
				.filter(fieldNode -> (fieldNode.access & Opcodes.ACC_FINAL) != 0)
				.collect(Collectors.toList());
		if (staticFinalFields.size() == 1) {
			return staticFinalFields.get(0).desc;
		}

		List<FieldNode> objectFields = matchingFields.stream()
				.filter(fieldNode -> fieldNode.desc.startsWith("L") || fieldNode.desc.startsWith("["))
				.collect(Collectors.toList());
		if (objectFields.size() == 1) {
			return objectFields.get(0).desc;
		}

		Set<String> distinctDescriptors = matchingFields.stream().map(fieldNode -> fieldNode.desc).collect(Collectors.toSet());
		if (distinctDescriptors.size() == 1) {
			return matchingFields.get(0).desc;
		}
		return null;
	}

	private static void visitClass(JarFile jarFile, JarEntry entry, Path tmpDir,
			Map<String, List<RequestedFieldAlias>> requestedFieldAliases,
			CompileRenameRegistry renameRegistry,
			CompileSymbolPlanner.CompileSymbolPlan symbolPlan,
			OutputStream out)
			throws IOException {
		ClassNode classNode = new ClassNode();

		InputStream classFileInputStream = jarFile.getInputStream(entry);
		try {
			ClassReader classReader = new ClassReader(classFileInputStream);
			classReader.accept(classNode, 0);
		} finally {
			classFileInputStream.close();
		}
		List<String> renameAliases = applyRenameAliases(classNode, renameRegistry.get(classNode.name));
		if (!renameAliases.isEmpty()) {
			writeLine(out, "[ApkSpy] Added JADX rename aliases for "
					+ classNode.name.replace('/', '.') + ": " + String.join(", ", renameAliases));
		}
		List<String> addedAliases = applyRequestedFieldAliases(classNode, requestedFieldAliases.get(classNode.name));
		if (!addedAliases.isEmpty()) {
			writeLine(out, "[ApkSpy] Added compile-time field aliases for "
					+ classNode.name.replace('/', '.') + ": " + String.join(", ", addedAliases));
		}
		boolean constructorBridgeAdded = applyRequestedConstructorBridge(classNode,
				symbolPlan.needsConstructorBridge(classNode.name));
		if (constructorBridgeAdded) {
			writeLine(out, "[ApkSpy] Added compile-time constructor bridge for "
					+ classNode.name.replace('/', '.'));
		}
		if (!renameAliases.isEmpty() || !addedAliases.isEmpty() || constructorBridgeAdded) {
			writeLine(out, "[ApkSpy] Stub fields for " + classNode.name.replace('/', '.') + ": "
					+ describeFields(classNode));
			writeLine(out, "[ApkSpy] Stub methods for " + classNode.name.replace('/', '.') + ": "
					+ describeMethods(classNode));
		}

		ClassWriter writer = new ClassWriter(0);
		writer.visit(classNode.version, classNode.access, classNode.name, classNode.signature, classNode.superName,
				classNode.interfaces.toArray(new String[0]));

		List<FieldNode> fieldNodes = classNode.fields;
		for (FieldNode fieldNode : fieldNodes) {
			writer.visitField(fieldNode.access, fieldNode.name, fieldNode.desc, fieldNode.signature, fieldNode.value);
		}

		List<MethodNode> methodNodes = classNode.methods;
		for (MethodNode methodNode : methodNodes) {
			MethodVisitor visitor = writer.visitMethod(methodNode.access, methodNode.name, methodNode.desc,
					methodNode.signature, methodNode.exceptions == null ? null : methodNode.exceptions.toArray(new String[0]));

			Type returnType = Type.getReturnType(methodNode.desc);

			visitor.visitCode();
			if ("<init>".equals(methodNode.name)) {
				visitor.visitVarInsn(Opcodes.ALOAD, 0);
				visitor.visitMethodInsn(Opcodes.INVOKESPECIAL,
						classNode.superName == null ? "java/lang/Object" : classNode.superName,
						"<init>", "()V", false);
				visitor.visitInsn(Opcodes.RETURN);
			} else if ("<clinit>".equals(methodNode.name)) {
				visitor.visitInsn(Opcodes.RETURN);
			} else {
				switch (returnType.getDescriptor()) {
					case "Z":
					case "B":
					case "S":
					case "I":
					case "C":
						visitor.visitInsn(Opcodes.ICONST_0);
						visitor.visitInsn(Opcodes.IRETURN);
						break;
					case "J":
						visitor.visitInsn(Opcodes.LCONST_0);
						visitor.visitInsn(Opcodes.LRETURN);
						break;
					case "F":
						visitor.visitInsn(Opcodes.FCONST_0);
						visitor.visitInsn(Opcodes.FRETURN);
						break;
					case "D":
						visitor.visitInsn(Opcodes.DCONST_0);
						visitor.visitInsn(Opcodes.DRETURN);
						break;
					case "V":
						visitor.visitInsn(Opcodes.RETURN);
						break;
					default:
						visitor.visitInsn(Opcodes.ACONST_NULL);
						visitor.visitInsn(Opcodes.ARETURN);
						break;
				}
			}

			visitor.visitMaxs(20, 20);
			visitor.visitEnd();
		}

		List<InnerClassNode> nodes = classNode.innerClasses;
		for (InnerClassNode node : nodes) {
			writer.visitInnerClass(node.name, node.outerName, node.innerName, node.access);
		}

		writer.visitEnd();
		byte[] bytes = writer.toByteArray();
		Path path = tmpDir.resolve(entry.getName());
		path.toFile().getParentFile().mkdirs();
		Files.write(path, bytes);
	}

	private static String describeFields(ClassNode classNode) {
		return classNode.fields.stream()
				.map(fieldNode -> fieldNode.name + ":" + fieldNode.desc + "@0x" + Integer.toHexString(fieldNode.access))
				.collect(Collectors.joining(", "));
	}

	private static String describeMethods(ClassNode classNode) {
		return classNode.methods.stream()
				.map(methodNode -> methodNode.name + methodNode.desc + "@0x" + Integer.toHexString(methodNode.access))
				.collect(Collectors.joining(", "));
	}

	static final class RequestedFieldAlias {
		private final String name;
		private final boolean arrayContext;

		RequestedFieldAlias(String name, boolean arrayContext) {
			this.name = name;
			this.arrayContext = arrayContext;
		}

		String getName() {
			return name;
		}

		boolean isArrayContext() {
			return arrayContext;
		}

		RequestedFieldAlias merge(boolean otherArrayContext) {
			return new RequestedFieldAlias(name, arrayContext || otherArrayContext);
		}
	}

	public static void pack(Path pp, Path zipFilePath) throws IOException {
		Path p = Files.createFile(zipFilePath);
		try (ZipOutputStream zs = new ZipOutputStream(Files.newOutputStream(p))) {
			Files.walk(pp).filter(path -> !Files.isDirectory(path)).forEach(path -> {
				ZipEntry zipEntry = new ZipEntry(pp.relativize(path).toString().replace('\\', '/'));
				try {
					zs.putNextEntry(zipEntry);
					Files.copy(path, zs);
					zs.closeEntry();
				} catch (IOException e) {
					LOG.error("Jar creation failed: ", e);
				}
			});
		}
	}
}
