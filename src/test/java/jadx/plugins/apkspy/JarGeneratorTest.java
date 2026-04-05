package jadx.plugins.apkspy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import jadx.plugins.apkspy.model.CompileRenameRegistry;

class JarGeneratorTest {

	@TempDir
	Path tempDir;

	@Test
	void packUsesPortableJarEntrySeparators() throws Exception {
		Path classesDir = tempDir.resolve("classes");
		Path classFile = classesDir.resolve(Paths.get("com", "example", "Test.class"));
		Files.createDirectories(classFile.getParent());
		Files.writeString(classFile, "stub", StandardCharsets.UTF_8);

		Path jarPath = tempDir.resolve("stub.jar");
		JarGenerator.pack(classesDir, jarPath);

		try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
			assertThat(zipFile.getEntry("com/example/Test.class")).isNotNull();
			assertThat(zipFile.getEntry("com\\example\\Test.class")).isNull();
		}
	}

	@Test
	void addsCompileTimeAliasFieldUsingExistingStaticDescriptor() {
		ClassNode classNode = new ClassNode();
		classNode.fields = new java.util.ArrayList<>();
		classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "a",
				"Landroid/security/keystore/KeyGenParameterSpec;", null, null));

		List<String> added = JarGenerator.applyRequestedFieldAliases(classNode,
				List.of(new JarGenerator.RequestedFieldAlias("f4544a", false)));

		assertThat(added).containsExactly("f4544a:Landroid/security/keystore/KeyGenParameterSpec;");
		assertThat(classNode.fields)
				.extracting(fieldNode -> fieldNode.name + ":" + fieldNode.desc)
				.contains("f4544a:Landroid/security/keystore/KeyGenParameterSpec;");
	}

	@Test
	void appliesJadxRenameAliasesForFieldsAndMethods() {
		ClassNode classNode = new ClassNode();
		classNode.name = "test/C";
		classNode.superName = "java/lang/Object";
		classNode.version = Opcodes.V17;
		classNode.access = Opcodes.ACC_PUBLIC;
		classNode.fields = new java.util.ArrayList<>();
		classNode.methods = new java.util.ArrayList<>();
		classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "a", "Ljava/lang/String;", null, null));
		classNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "b", "I", null, null));
		classNode.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "c", "(Ljava/lang/String;)V", null, null));

		CompileRenameRegistry.RenamedClassInfo renamedClassInfo = new CompileRenameRegistry.RenamedClassInfo(
				"test/C", "test.C", "test.C");
		renamedClassInfo.addFieldAlias("a", "f4544a");
		renamedClassInfo.addFieldAlias("b", "renamedValue");
		renamedClassInfo.addMethodAlias("c", 1, "renamedMethod");

		List<String> added = JarGenerator.applyRenameAliases(classNode, renamedClassInfo);

		assertThat(added).contains("field f4544a->a", "field renamedValue->b", "method renamedMethod->c/1");
		assertThat(classNode.fields)
				.extracting(fieldNode -> fieldNode.name + ":" + fieldNode.desc)
				.contains("f4544a:Ljava/lang/String;", "renamedValue:I");
		assertThat(classNode.methods)
				.extracting(methodNode -> methodNode.name + methodNode.desc)
				.contains("renamedMethod(Ljava/lang/String;)V");
	}

	@Test
	void compilesAgainstRenamedStaticFieldAlias() throws Exception {
		ClassNode classNode = createClassNode("q1/c", "java/lang/Object");
		classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "a",
				"Ljava/lang/String;", null, null));

		CompileRenameRegistry.RenamedClassInfo renamedClassInfo = new CompileRenameRegistry.RenamedClassInfo(
				"q1/c", "q1.c", "q1.c");
		renamedClassInfo.addFieldAlias("a", "f4544a");
		JarGenerator.applyRenameAliases(classNode, renamedClassInfo);

		Path jarPath = writeStubJar(Map.of("q1/c", classNode));
		assertCompilesAgainstJar(jarPath, """
				import q1.c;

				public class LiquidIslandApplication {
				    String value() {
				        return c.f4544a;
				    }
				}
				""");
	}

	@Test
	void compilesAgainstInheritedAliasStaticMethodAliasAndConstructorBridge() throws Exception {
		ClassNode baseClass = createClassNode("test/Base", "java/lang/Object");
		baseClass.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "a", "Ljava/lang/String;", null, null));
		baseClass.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null));

		CompileRenameRegistry.RenamedClassInfo baseRenames = new CompileRenameRegistry.RenamedClassInfo(
				"test/Base", "test.Base", "test.Base");
		baseRenames.addFieldAlias("a", "f7482a");
		JarGenerator.applyRenameAliases(baseClass, baseRenames);
		JarGenerator.applyRequestedConstructorBridge(baseClass, true);

		ClassNode utilClass = createClassNode("test/U", "java/lang/Object");
		utilClass.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "N", "()Ljava/lang/String;", null, null));
		CompileRenameRegistry.RenamedClassInfo utilRenames = new CompileRenameRegistry.RenamedClassInfo(
				"test/U", "test.U", "test.U");
		utilRenames.addMethodAlias("N", 0, "robux");
		JarGenerator.applyRenameAliases(utilClass, utilRenames);

		Path jarPath = writeStubJar(Map.of(
				"test/Base", baseClass,
				"test/U", utilClass));
		assertCompilesAgainstJar(jarPath, """
				package test;

				public class Child extends Base {
				    public Child() {
				        super("ctx");
				    }

				    public String value() {
				        return this.f7482a + U.robux();
				    }
				}
				""");
	}

	private ClassNode createClassNode(String internalName, String superName) {
		ClassNode classNode = new ClassNode();
		classNode.name = internalName;
		classNode.superName = superName;
		classNode.version = Opcodes.V17;
		classNode.access = Opcodes.ACC_PUBLIC;
		classNode.fields = new java.util.ArrayList<>();
		classNode.methods = new java.util.ArrayList<>();
		classNode.interfaces = new java.util.ArrayList<>();
		return classNode;
	}

	private Path writeStubJar(Map<String, ClassNode> classes) throws Exception {
		Path classesDir = tempDir.resolve("stub-classes-" + classes.size());
		Files.createDirectories(classesDir);
		for (Map.Entry<String, ClassNode> entry : new LinkedHashMap<>(classes).entrySet()) {
			Path classPath = classesDir.resolve(entry.getKey() + ".class");
			Files.createDirectories(classPath.getParent());
			Files.write(classPath, toClassBytes(entry.getValue()));
		}
		Path jarPath = tempDir.resolve("stub-" + classes.hashCode() + ".jar");
		JarGenerator.pack(classesDir, jarPath);
		return jarPath;
	}

	private byte[] toClassBytes(ClassNode classNode) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(classNode.version, classNode.access, classNode.name, classNode.signature, classNode.superName,
				classNode.interfaces.toArray(new String[0]));
		for (FieldNode fieldNode : classNode.fields) {
			writer.visitField(fieldNode.access, fieldNode.name, fieldNode.desc, fieldNode.signature, fieldNode.value);
		}
		for (MethodNode methodNode : classNode.methods) {
			MethodVisitor visitor = writer.visitMethod(methodNode.access, methodNode.name, methodNode.desc,
					methodNode.signature, methodNode.exceptions == null ? null : methodNode.exceptions.toArray(new String[0]));
			visitor.visitCode();
			if ("<init>".equals(methodNode.name)) {
				visitor.visitVarInsn(Opcodes.ALOAD, 0);
				visitor.visitMethodInsn(Opcodes.INVOKESPECIAL,
						classNode.superName == null ? "java/lang/Object" : classNode.superName,
						"<init>", "()V", false);
				visitor.visitInsn(Opcodes.RETURN);
			} else {
				switch (Type.getReturnType(methodNode.desc).getDescriptor()) {
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
			visitor.visitMaxs(4, 4);
			visitor.visitEnd();
		}
		writer.visitEnd();
		return writer.toByteArray();
	}

	private void assertCompilesAgainstJar(Path jarPath, String source) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).isNotNull();

		Path sourceDir = tempDir.resolve("compile-src-" + Math.abs(source.hashCode()));
		Path outputDir = tempDir.resolve("compile-out-" + Math.abs(source.hashCode()));
		Files.createDirectories(sourceDir);
		Files.createDirectories(outputDir);
		Path sourceFile = sourceDir.resolve(findTopLevelTypeName(source) + ".java");
		Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(sourceFile.toFile());
			ByteArrayOutputStream compilerOut = new ByteArrayOutputStream();
			boolean success = compiler.getTask(
					new java.io.OutputStreamWriter(compilerOut, StandardCharsets.UTF_8),
					fileManager,
					diagnostics,
					List.of("-classpath", jarPath.toString(), "-d", outputDir.toString()),
					null,
					compilationUnits).call();
			String diagnosticText = diagnostics.getDiagnostics().stream()
					.map(Object::toString)
					.collect(java.util.stream.Collectors.joining("\n"));
			assertThat(success)
					.withFailMessage("Compilation failed:\n%s\n%s", diagnosticText,
							compilerOut.toString(StandardCharsets.UTF_8))
					.isTrue();
		}
	}

	private String findTopLevelTypeName(String source) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("\\b(?:class|interface|enum|@interface)\\s+(\\w+)\\b")
				.matcher(source);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}
}
