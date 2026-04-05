package jadx.plugins.apkspy.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.body.VariableDeclarator;

import jadx.plugins.apkspy.model.DiffMatchPatch.Diff;

public class ClassBreakdown implements Cloneable {
	public static final int BLOCK_STATIC = 4;
	private String className;
	private String simpleName;
	private String imports;
	private String classDeclaration;
	private String memberVariables;
	private List<JavaMethod> changedMethods;
	private List<JavaMethod> methods;
	private List<ClassBreakdown> innerClasses;

	/**
	 * @param className  full qualified class name
	 * @param simpleName
	 * @param content    source code formatted with 4 space indentation
	 * @return
	 */
	public static ClassBreakdown breakdown(String className, String simpleName, String content) {
		String[] split = content.split("\n");

		String imports = "";
		String classDeclaration = "";
		String memberVariables = "";
		List<JavaMethod> methods = new ArrayList<>();
		List<ClassBreakdown> innerClasses = new ArrayList<>();
		String currentBlock = "";
		String pendingClassDeclaration = null;
		int blockType = 0;
		boolean allowRoot = true;
		for (String line : split) {
			if (allowRoot) {
				if (!line.startsWith(" ")) {
					if (pendingClassDeclaration != null) {
						pendingClassDeclaration += " " + line.trim();
						if (pendingClassDeclaration.contains("{")) {
							classDeclaration = pendingClassDeclaration.substring(0, pendingClassDeclaration.indexOf("{")).trim();
							if (simpleName == null) {
								Matcher m = Pattern.compile(".*\\b(class|interface|enum|@interface)\\s+([^\\s<{]+).*")
										.matcher(classDeclaration);
								if (m.find()) {
									simpleName = m.group(2);
								}
							}
							allowRoot = false;
							pendingClassDeclaration = null;
						}
					} else if (line.contains("class ") || line.contains("interface ") || line.contains("enum ")
							|| line.contains("@interface ")) {
						pendingClassDeclaration = line.trim();
						if (pendingClassDeclaration.contains("{")) {
							classDeclaration = pendingClassDeclaration.substring(0, pendingClassDeclaration.indexOf("{")).trim();
							if (simpleName == null) {
								Matcher m = Pattern.compile(".*\\b(class|interface|enum|@interface)\\s+([^\\s<{]+).*")
										.matcher(classDeclaration);
								if (m.find()) {
									simpleName = m.group(2);
								}
							}
							allowRoot = false;
							pendingClassDeclaration = null;
						}
					} else {
						imports += line.trim() + "\n";
					}
				}
			} else {
				if (line.startsWith("    ") && !line.startsWith("     ")) {
					if (line.trim().equals("}")) {
						if (blockType == 1) {
							methods.add(new JavaMethod(currentBlock.trim() + "\n}"));
						} else if (blockType == 2) {
							innerClasses.add(ClassBreakdown.breakdown(null, null, currentBlock.trim() + "\n}"));
						}
						currentBlock = "";
						blockType = 0;
					} else if (line.trim().equals("static {")) {
						currentBlock = "";
						blockType = BLOCK_STATIC;
					} else if (line.trim().equals("};") && blockType == 3) {
						memberVariables += currentBlock + "};\n";
						currentBlock = "";
						blockType = 0;
					} else if (line.trim().endsWith(";")) {
						memberVariables += line.trim() + "\n";
					} else {
						if (line.contains("new ")) {
							blockType = 3;
						} else if (line.contains("class ")) {
							blockType = 2;
						} else {
							blockType = 1;
						}
						currentBlock += StringUtils.stripEnd(line.substring(4), "\r\n ") + "\n";
					}
				} else if (line.startsWith("     ")) {
					currentBlock += StringUtils.stripEnd(line.substring(4), "\r\n ") + "\n";
				}
			}
		}

		if (!currentBlock.isEmpty()) {
			if (blockType == 1) {
				methods.add(new JavaMethod(currentBlock));
			} else if (blockType == 2) {
				innerClasses.add(ClassBreakdown.breakdown(null, null, currentBlock.trim() + "\n}"));
			} else if (blockType == 3) {
				memberVariables += currentBlock + "};\n";
			}
		}

		if (allowRoot) {
			throw new IllegalArgumentException("Could not parse class declaration from source");
		}

		return new ClassBreakdown(imports, classDeclaration, className, simpleName, memberVariables, methods,
				innerClasses);
	}

	public ClassBreakdown(String imports, String classDeclaration, String className, String simpleName,
			String memberVariables, List<JavaMethod> methods, List<ClassBreakdown> innerClasses) {
		this.imports = imports;
		this.classDeclaration = classDeclaration;
		this.className = className;
		this.simpleName = simpleName;
		this.memberVariables = memberVariables;
		this.methods = methods;
		this.changedMethods = methods;
		this.innerClasses = innerClasses;
	}

	public ClassBreakdown(ClassBreakdown old) {
		this.imports = old.imports;
		this.classDeclaration = old.classDeclaration;
		this.className = old.className;
		this.simpleName = old.simpleName;
		this.memberVariables = old.memberVariables;
		this.methods = new ArrayList<>(old.methods);
		this.changedMethods = new ArrayList<>(old.changedMethods);
		this.innerClasses = new ArrayList<>(old.innerClasses);
	}

	public String getImports() {
		return imports;
	}

	public void setImports(String imports) {
		this.imports = imports;
	}

	public String getClassDeclaration() {
		return classDeclaration;
	}

	public void setClassDeclaration(String classDeclaration) {
		this.classDeclaration = classDeclaration;
	}

	public String getMemberVariables() {
		return memberVariables;
	}

	public void setMemberVariables(String memberVariables) {
		this.memberVariables = memberVariables;
	}

	public List<JavaMethod> getMethods() {
		return methods;
	}

	public void setMethods(List<JavaMethod> methods) {
		this.methods = methods;
	}

	public String getFullName() {
		return className;
	}

	public void setFullName(String className) {
		this.className = className;
	}

	public List<JavaMethod> getChangedMethods() {
		return changedMethods;
	}

	public void setChangedMethods(List<JavaMethod> methods) {
		this.changedMethods = methods;
	}

	public String getSimpleName() {
		return simpleName;
	}

	public void setSimpleName(String simpleName) {
		this.simpleName = simpleName;
	}

	private static String methodKey(JavaMethod method) {
		try {
			BodyDeclaration<?> declaration = StaticJavaParser.parseBodyDeclaration(method.getMethod());
			if (declaration.isConstructorDeclaration()) {
				return declaration.asConstructorDeclaration().getSignature().asString();
			}
			if (declaration.isMethodDeclaration()) {
				return declaration.asMethodDeclaration().getSignature().asString();
			}
		} catch (RuntimeException ignore) {
			// fall through to string-based fallback
		}

		String header = method.getHeader();
		String cleanedHeader = header.replaceAll("(?m)^\\s*@.*\\R?", "").replaceAll("\\s+", " ").trim();
		int paramsStart = cleanedHeader.indexOf('(');
		int paramsEnd = cleanedHeader.lastIndexOf(')');
		if (paramsStart == -1 || paramsEnd == -1 || paramsEnd < paramsStart) {
			return cleanedHeader;
		}
		String beforeParams = cleanedHeader.substring(0, paramsStart).trim();
		String name = beforeParams.substring(beforeParams.lastIndexOf(' ') + 1);
		String params = cleanedHeader.substring(paramsStart, paramsEnd + 1);
		return name + params;
	}

	private static boolean isLiteralInitializer(Expression expression) {
		if (expression == null) {
			return false;
		}
		if (expression instanceof BooleanLiteralExpr
				|| expression instanceof CharLiteralExpr
				|| expression instanceof DoubleLiteralExpr
				|| expression instanceof IntegerLiteralExpr
				|| expression instanceof LongLiteralExpr
				|| expression instanceof NullLiteralExpr
				|| expression instanceof StringLiteralExpr) {
			return true;
		}
		if (expression instanceof UnaryExpr) {
			UnaryExpr unary = (UnaryExpr) expression;
			return EnumSet.of(UnaryExpr.Operator.MINUS, UnaryExpr.Operator.PLUS).contains(unary.getOperator())
					&& isLiteralInitializer(unary.getExpression());
		}
		return false;
	}

	private static String sanitizeFieldDeclarationsFallback(String memberVariables) {
		StringBuilder builder = new StringBuilder();
		StringBuilder current = new StringBuilder();
		int braceDepth = 0;

		for (String line : memberVariables.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}

			if (current.length() > 0) {
				current.append(' ');
			}
			current.append(trimmed);

			for (char ch : trimmed.toCharArray()) {
				if (ch == '{') {
					braceDepth++;
				} else if (ch == '}') {
					braceDepth--;
				}
			}

			if (braceDepth <= 0 && trimmed.endsWith(";")) {
				String declaration = current.toString();
				int initializerStart = declaration.indexOf('=');
				if (initializerStart >= 0) {
					declaration = declaration.substring(0, initializerStart).trim() + ";";
				}
				declaration = declaration.replaceAll("\\bfinal\\b\\s*", "").trim();
				builder.append(declaration).append('\n');
				current.setLength(0);
				braceDepth = 0;
			}
		}
		return builder.toString().trim();
	}

	private static String sanitizeFieldDeclarations(String memberVariables) {
		if (memberVariables == null || memberVariables.trim().isEmpty()) {
			return "";
		}
		try {
			TypeDeclaration<?> dummyType = StaticJavaParser.parse("class Dummy {\n" + memberVariables + "\n}")
					.getType(0);
			if (!(dummyType instanceof ClassOrInterfaceDeclaration)) {
				return memberVariables;
			}

			StringBuilder builder = new StringBuilder();
			for (BodyDeclaration<?> member : ((ClassOrInterfaceDeclaration) dummyType).getMembers()) {
				if (!member.isFieldDeclaration()) {
					continue;
				}

				FieldDeclaration field = member.asFieldDeclaration().clone();
				boolean keepFinal = field.isStatic()
						&& field.getVariables().stream()
								.map(VariableDeclarator::getInitializer)
								.allMatch(initializer -> initializer.isPresent() && isLiteralInitializer(initializer.get()));

				field.getAnnotations().clear();
				if (!keepFinal) {
					field.removeModifier(Modifier.Keyword.FINAL);
				}

				for (VariableDeclarator variable : field.getVariables()) {
					if (!keepFinal) {
						variable.removeInitializer();
					}
				}

				builder.append(field.toString().trim()).append('\n');
			}
			return builder.toString().trim();
		} catch (RuntimeException ignore) {
			return sanitizeFieldDeclarationsFallback(memberVariables);
		}
	}

	private String extractCallableName(String header) {
		int parenIndex = header.indexOf('(');
		if (parenIndex < 0) {
			return "";
		}

		String beforeParen = header.substring(0, parenIndex).trim();
		int lastSpace = Math.max(beforeParen.lastIndexOf(' '), beforeParen.lastIndexOf('\t'));
		if (lastSpace >= 0) {
			return beforeParen.substring(lastSpace + 1).trim();
		}
		return beforeParen;
	}

	private boolean isConstructorHeader(String header) {
		String callableName = extractCallableName(header);
		return callableName.equals(this.simpleName)
				|| callableName.equals("ApkSpy_" + this.simpleName);
	}

	public ClassBreakdown addOrReplaceMethod(JavaMethod newMethod) {
		if (newMethod == null) {
			return this;
		}
		ClassBreakdown clone = new ClassBreakdown(this);

		String methodKey = methodKey(newMethod);
		boolean changedMethodReplaced = false;
		for (int i = 0; i < methods.size(); i++) {
			if (methodKey.equals(methodKey(methods.get(i)))) {
				clone.methods.set(i, newMethod);
				break;
			}
		}

		for (int i = 0; i < clone.changedMethods.size(); i++) {
			if (methodKey.equals(methodKey(clone.changedMethods.get(i)))) {
				clone.changedMethods.set(i, newMethod);
				changedMethodReplaced = true;
				break;
			}
		}

		boolean methodExists = clone.methods.stream().anyMatch(method -> methodKey.equals(methodKey(method)));
		if (!methodExists) {
			clone.methods.add(newMethod);
		}
		if (!changedMethodReplaced) {
			clone.changedMethods.add(newMethod);
		}
		return clone;
	}

	public ClassBreakdown mergeImports(String imports) {
		DiffMatchPatch dmp = new DiffMatchPatch();
		List<Diff> diffs = dmp.diffMain(this.imports, imports);

		ClassBreakdown clone = new ClassBreakdown(this);
		clone.imports = dmp.diffText2(diffs);
		return clone;
	}

	public ClassBreakdown mergeMemberVariables(String memberVariables) {
		DiffMatchPatch dmp = new DiffMatchPatch();
		List<Diff> diffs = dmp.diffMain(this.memberVariables, memberVariables);

		ClassBreakdown clone = new ClassBreakdown(this);
		clone.memberVariables = dmp.diffText2(diffs);
		return clone;
	}

	public ClassBreakdown mergeFieldStubs(String memberVariables) {
		ClassBreakdown clone = new ClassBreakdown(this);
		clone.memberVariables = sanitizeFieldDeclarations(memberVariables);
		return clone;
	}

	private JavaMethod toStub(JavaMethod method) {
		String header = method.getHeader();
		String containing = header.substring(0, header.indexOf('(')).trim();
		boolean isConstructor = isConstructorHeader(header);
		String returnType = null;

		try {
			BodyDeclaration<?> declaration = StaticJavaParser.parseBodyDeclaration(method.getMethod());
			if (declaration.isConstructorDeclaration()) {
				isConstructor = true;
			} else if (declaration.isMethodDeclaration()) {
				MethodDeclaration methodDeclaration = declaration.asMethodDeclaration();
				returnType = methodDeclaration.getType().asString();
				header = header.replaceAll("\\babstract\\s+", "").replaceAll("\\bnative\\s+", "");
			}
		} catch (RuntimeException ignore) {
			header = header.replaceAll("\\babstract\\s+", "").replaceAll("\\bnative\\s+", "");
		}

		String stub = header + "\n";
		if (!isConstructor && returnType != null) {
			switch (returnType) {
				case "byte":
				case "short":
				case "int":
				case "long":
					stub += "    return 0;\n";
					break;
				case "float":
					stub += "    return 0.0f;\n";
					break;
				case "double":
					stub += "    return 0.0;\n";
					break;
				case "char":
					stub += "    return ' ';\n";
					break;
				case "boolean":
					stub += "    return false;\n";
					break;
				case "void":
					stub += "    return;\n";
					break;
				default:
					stub += "    return null;\n";
					break;
			}
		} else if (containing.contains("byte ") || containing.contains("short ") || containing.contains("int ")
				|| containing.contains("long ")) {
			stub += "    return 0;\n";
		} else if (containing.contains("float ")) {
			stub += "    return 0.0f;\n";
		} else if (containing.contains("double ")) {
			stub += "    return 0.0;\n";
		} else if (containing.contains("char ")) {
			stub += "    return ' ';\n";
		} else if (containing.contains("boolean ")) {
			stub += "    return false;\n";
		} else if (containing.contains("void ") || isConstructor) {
			stub += "    return;\n";
		} else {
			stub += "    return null;\n";
		}
		stub += "}";

		return new JavaMethod(stub);
	}

	public ClassBreakdown mergeMethodStubs(List<JavaMethod> methods) {
		ClassBreakdown clone = new ClassBreakdown(this);
		outer: for (JavaMethod newMethod : methods) {
			String methodKey = methodKey(newMethod);
			for (int i = 0; i < this.methods.size(); i++) {
				if (methodKey.equals(methodKey(this.methods.get(i)))) {
					continue outer;
				}
			}

			clone.methods.add(this.toStub(newMethod));
		}

		return clone;
	}

	public ClassBreakdown mergeMethods(List<JavaMethod> methods) {
		ClassBreakdown breakdown = new ClassBreakdown(this);
		for (JavaMethod method : methods) {
			breakdown = breakdown.addOrReplaceMethod(method);
		}
		return breakdown;
	}

	public ClassBreakdown mergeInnerClassStubs(ClassBreakdown original) {
		ClassBreakdown breakdown = new ClassBreakdown(this);
		breakdown.innerClasses = original.innerClasses.stream().map(cls -> cls.asStub()).collect(Collectors.toList());
		return breakdown;
	}

	public List<ClassBreakdown> getInnerClasses() {
		return innerClasses;
	}

	public void setInnerClasses(List<ClassBreakdown> innerClasses) {
		this.innerClasses = innerClasses;
	}

	public ClassBreakdown asStub() {
		ClassBreakdown breakdown = new ClassBreakdown(this);
		breakdown.memberVariables = sanitizeFieldDeclarations(this.memberVariables);
		breakdown.methods = this.methods.stream().map(this::toStub).collect(Collectors.toList());
		breakdown.changedMethods = new ArrayList<>(breakdown.methods);
		breakdown.innerClasses = this.innerClasses.stream().map(ClassBreakdown::asStub).collect(Collectors.toList());
		return breakdown;
	}

	@Override
	public String toString() {
		StringBuilder str = new StringBuilder(this.imports);

		str.append((this.classDeclaration + " {\n").replaceAll("(.*?)(class|interface|enum|@interface) (.+?) (.+)",
				"$1$2 " + this.simpleName + " $4"));
		if (this.memberVariables.length() > 0) {
			for (String member : this.memberVariables.split("\n")) {
				str.append("    " + member + "\n");
			}
			str.append("\n");
		}
		if (this.innerClasses.size() > 0) {
			for (ClassBreakdown innerClass : this.innerClasses) {
				String toStr = innerClass.toString();
				for (String split : toStr.split("\n")) {
					str.append("    " + split + "\n");
				}
				str.append("\n");
			}
		}
		if (this.methods.size() > 0) {
			for (JavaMethod method : this.methods) {
				for (String split : method.toString().split("\n")) {
					str.append("    " + split + "\n");
				}
				str.append("\n");
			}
		}
		return str.toString().substring(0, str.length() - 1) + "}";
	}
}
