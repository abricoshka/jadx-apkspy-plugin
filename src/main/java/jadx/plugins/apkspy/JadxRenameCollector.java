package jadx.plugins.apkspy;

import java.util.HashSet;
import java.util.Set;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.plugins.apkspy.model.CompileRenameRegistry;

public final class JadxRenameCollector {

	private JadxRenameCollector() {
	}

	public static CompileRenameRegistry collect(JadxDecompiler decompiler, ClassNode classNode) {
		CompileRenameRegistry registry = new CompileRenameRegistry();
		JavaClass javaClass = classNode.getJavaNode();
		if (javaClass == null) {
			javaClass = decompiler.searchJavaClassByAliasFullName(classNode.getFullName());
		}
		if (javaClass == null) {
			javaClass = decompiler.searchJavaClassByOrigFullName(classNode.getRawName());
		}
		if (javaClass == null) {
			return registry;
		}

		Set<String> visited = new HashSet<>();
		collectClass(javaClass, registry, visited);
		for (JavaClass dependency : javaClass.getDependencies()) {
			collectClass(dependency, registry, visited);
		}
		return registry;
	}

	private static void collectClass(JavaClass javaClass, CompileRenameRegistry registry, Set<String> visited) {
		if (!visited.add(javaClass.getRawName())) {
			return;
		}

		String rawInternalName = javaClass.getRawName().replace('.', '/');
		CompileRenameRegistry.RenamedClassInfo classInfo = registry.getOrCreate(rawInternalName,
				javaClass.getRawName(), javaClass.getFullName());

		for (JavaField field : javaClass.getFields()) {
			String rawFieldName = field.getFieldNode().getName();
			String aliasFieldName = field.getName();
			if (!aliasFieldName.equals(rawFieldName)) {
				classInfo.addFieldAlias(rawFieldName, aliasFieldName);
			}
		}

		for (JavaMethod method : javaClass.getMethods()) {
			MethodNode methodNode = method.getMethodNode();
			String rawMethodName = methodNode.getName();
			String aliasMethodName = method.getName();
			if (!aliasMethodName.equals(rawMethodName)) {
				classInfo.addMethodAlias(rawMethodName, method.getArguments().size(), aliasMethodName);
			}
		}

		for (JavaClass innerClass : javaClass.getInnerClasses()) {
			collectClass(innerClass, registry, visited);
		}
	}
}
