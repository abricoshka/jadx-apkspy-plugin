package jadx.plugins.apkspy.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class CompileRenameRegistry {

	private final Map<String, RenamedClassInfo> classesByRawInternalName = new LinkedHashMap<>();

	public RenamedClassInfo getOrCreate(String rawInternalName, String rawFullName, String aliasFullName) {
		return classesByRawInternalName.computeIfAbsent(rawInternalName,
				key -> new RenamedClassInfo(rawInternalName, rawFullName, aliasFullName));
	}

	public RenamedClassInfo get(String rawInternalName) {
		return classesByRawInternalName.get(rawInternalName);
	}

	public Map<String, RenamedClassInfo> getClasses() {
		return Collections.unmodifiableMap(classesByRawInternalName);
	}

	public String findRawInternalNameByAliasOrRawFullName(String fullName) {
		for (RenamedClassInfo classInfo : classesByRawInternalName.values()) {
			if (fullName.equals(classInfo.getAliasFullName()) || fullName.equals(classInfo.getRawFullName())) {
				return classInfo.getRawInternalName();
			}
		}
		return null;
	}

	public void merge(CompileRenameRegistry other) {
		for (RenamedClassInfo otherInfo : other.classesByRawInternalName.values()) {
			getOrCreate(otherInfo.getRawInternalName(), otherInfo.getRawFullName(), otherInfo.getAliasFullName())
					.merge(otherInfo);
		}
	}

	public static class RenamedClassInfo {
		private final String rawInternalName;
		private final String rawFullName;
		private String aliasFullName;
		private final Map<String, String> rawToAliasFieldNames = new LinkedHashMap<>();
		private final Map<RenamedMethodKey, String> methodAliases = new LinkedHashMap<>();

		public RenamedClassInfo(String rawInternalName, String rawFullName, String aliasFullName) {
			this.rawInternalName = rawInternalName;
			this.rawFullName = rawFullName;
			this.aliasFullName = aliasFullName;
		}

		public String getRawInternalName() {
			return rawInternalName;
		}

		public String getRawFullName() {
			return rawFullName;
		}

		public String getAliasFullName() {
			return aliasFullName;
		}

		public Map<String, String> getRawToAliasFieldNames() {
			return Collections.unmodifiableMap(rawToAliasFieldNames);
		}

		public Map<RenamedMethodKey, String> getMethodAliases() {
			return Collections.unmodifiableMap(methodAliases);
		}

		public void addFieldAlias(String rawName, String aliasName) {
			rawToAliasFieldNames.put(rawName, aliasName);
		}

		public void addMethodAlias(String rawName, int argCount, String aliasName) {
			methodAliases.put(new RenamedMethodKey(rawName, argCount), aliasName);
		}

		public void merge(RenamedClassInfo other) {
			this.aliasFullName = other.aliasFullName;
			this.rawToAliasFieldNames.putAll(other.rawToAliasFieldNames);
			this.methodAliases.putAll(other.methodAliases);
		}
	}

	public static class RenamedMethodKey {
		private final String rawName;
		private final int argCount;

		public RenamedMethodKey(String rawName, int argCount) {
			this.rawName = rawName;
			this.argCount = argCount;
		}

		public String getRawName() {
			return rawName;
		}

		public int getArgCount() {
			return argCount;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof RenamedMethodKey)) {
				return false;
			}
			RenamedMethodKey that = (RenamedMethodKey) other;
			return argCount == that.argCount && rawName.equals(that.rawName);
		}

		@Override
		public int hashCode() {
			return 31 * rawName.hashCode() + argCount;
		}
	}
}
