package jadx.plugins.apkspy;

import java.util.Locale;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.options.impl.BasePluginOptionsBuilder;

public class ApkSpyOptions extends BasePluginOptionsBuilder {
	private static final Logger LOG = LoggerFactory.getLogger(ApkSpyOptions.class);
	private static final Preferences SIGNING_PREFS =
			Preferences.userNodeForPackage(ApkSpyOptions.class).node("signing");
	private static final String PREF_SIGNING_MODE = "mode";
	private static final String PREF_SIGNING_KEYSTORE_PATH = "keystorePath";
	private static final String PREF_SIGNING_KEY_ALIAS = "keyAlias";
	private static final String PREF_SIGNING_SCHEME = "scheme";
	private static final String PREF_SIGNING_V1_SIGNER_NAME = "v1SignerName";

	private String androidSdkPath;

	private String jdkLocation;

	private String apktoolLocation;

	private SigningMode signingMode = SigningMode.DEBUG;

	private String signingKeystorePath = "";

	private String signingKeyAlias = "";

	private SigningScheme signingScheme = SigningScheme.V1_V2_V3;

	private String signingV1SignerName = "CERT";

	private transient String signingStorePassword = "";

	private transient String signingKeyPassword = "";

	public ApkSpyOptions() {
		loadSigningPreferences();
	}

	@Override
	public void registerOptions() {
		strOption(ApkSpyPlugin.PLUGIN_ID + ".androidSdk.path")
				.description("Android SDK location")
				.defaultValue("")
				.setter(v -> androidSdkPath = v);

		strOption(ApkSpyPlugin.PLUGIN_ID + ".jdkLocation.path")
				.description("JDK location (Java 17 or greater)")
				.defaultValue("")
				.setter(v -> jdkLocation = v);

		strOption(ApkSpyPlugin.PLUGIN_ID + ".apktoolLocation.path")
				.description("Apktool location")
				.defaultValue("")
				.setter(v -> apktoolLocation = v);
	}

	public String getAndroidSdkPath() {
		return androidSdkPath;
	}

	public String getJdkLocation() {
		return jdkLocation;
	}

	public String getApktoolLocation() {
		return apktoolLocation;
	}

	public SigningConfig getSigningConfig() {
		return new SigningConfig(signingMode, signingKeystorePath, signingKeyAlias, signingScheme, signingV1SignerName,
				signingStorePassword, signingKeyPassword);
	}

	public void updateSigningConfig(SigningConfig config) {
		signingMode = config.getMode();
		signingKeystorePath = config.getKeystorePath();
		signingKeyAlias = config.getKeyAlias();
		signingScheme = config.getScheme();
		signingV1SignerName = config.getV1SignerName();
		signingStorePassword = config.getStorePassword();
		signingKeyPassword = config.getKeyPassword();
		saveSigningPreferences();
	}

	private void loadSigningPreferences() {
		signingMode = SigningMode.parse(SIGNING_PREFS.get(PREF_SIGNING_MODE, SigningMode.DEBUG.name()));
		signingKeystorePath = SIGNING_PREFS.get(PREF_SIGNING_KEYSTORE_PATH, "");
		signingKeyAlias = SIGNING_PREFS.get(PREF_SIGNING_KEY_ALIAS, "");
		signingScheme = SigningScheme.parse(SIGNING_PREFS.get(PREF_SIGNING_SCHEME, SigningScheme.V1_V2_V3.name()));
		signingV1SignerName = SIGNING_PREFS.get(PREF_SIGNING_V1_SIGNER_NAME, "CERT");
	}

	private void saveSigningPreferences() {
		SIGNING_PREFS.put(PREF_SIGNING_MODE, signingMode.name());
		SIGNING_PREFS.put(PREF_SIGNING_KEYSTORE_PATH, signingKeystorePath);
		SIGNING_PREFS.put(PREF_SIGNING_KEY_ALIAS, signingKeyAlias);
		SIGNING_PREFS.put(PREF_SIGNING_SCHEME, signingScheme.name());
		SIGNING_PREFS.put(PREF_SIGNING_V1_SIGNER_NAME, signingV1SignerName);
		try {
			SIGNING_PREFS.flush();
		} catch (BackingStoreException e) {
			LOG.warn("Could not persist signing preferences", e);
		}
	}

	public enum SigningMode {
		DEBUG("Debug/test key"),
		CUSTOM("Custom keystore");

		private final String label;

		SigningMode(String label) {
			this.label = label;
		}

		public String getLabel() {
			return label;
		}

		@Override
		public String toString() {
			return label;
		}

		public static SigningMode parse(String value) {
			if (value == null || value.isBlank()) {
				return DEBUG;
			}
			try {
				return valueOf(value.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return DEBUG;
			}
		}
	}

	public enum SigningScheme {
		V1_V2_V3("V1 + V2 + V3", true, true, true),
		V1_V2("V1 + V2", true, true, false),
		V1_V3("V1 + V3", true, false, true),
		V1("V1", true, false, false),
		V2_V3("V2 + V3 (Android 7.0+)", false, true, true),
		V2("V2 (Android 7.0+)", false, true, false),
		V3("V3 (Android 9.0+)", false, false, true);

		private final String label;
		private final boolean v1Enabled;
		private final boolean v2Enabled;
		private final boolean v3Enabled;

		SigningScheme(String label, boolean v1Enabled, boolean v2Enabled, boolean v3Enabled) {
			this.label = label;
			this.v1Enabled = v1Enabled;
			this.v2Enabled = v2Enabled;
			this.v3Enabled = v3Enabled;
		}

		public String getLabel() {
			return label;
		}

		@Override
		public String toString() {
			return label;
		}

		public boolean isV1Enabled() {
			return v1Enabled;
		}

		public boolean isV2Enabled() {
			return v2Enabled;
		}

		public boolean isV3Enabled() {
			return v3Enabled;
		}

		public static SigningScheme parse(String value) {
			if (value == null || value.isBlank()) {
				return V1_V2_V3;
			}
			try {
				return valueOf(value.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return V1_V2_V3;
			}
		}
	}

	public static final class SigningConfig {
		private final SigningMode mode;
		private final String keystorePath;
		private final String keyAlias;
		private final SigningScheme scheme;
		private final String v1SignerName;
		private final String storePassword;
		private final String keyPassword;

		public SigningConfig(SigningMode mode, String keystorePath, String keyAlias, SigningScheme scheme,
				String v1SignerName, String storePassword, String keyPassword) {
			this.mode = mode == null ? SigningMode.DEBUG : mode;
			this.keystorePath = keystorePath == null ? "" : keystorePath.trim();
			this.keyAlias = keyAlias == null ? "" : keyAlias.trim();
			this.scheme = scheme == null ? SigningScheme.V1_V2_V3 : scheme;
			this.v1SignerName = v1SignerName == null || v1SignerName.isBlank() ? "CERT" : v1SignerName.trim();
			this.storePassword = storePassword == null ? "" : storePassword;
			this.keyPassword = keyPassword == null ? "" : keyPassword;
		}

		public SigningMode getMode() {
			return mode;
		}

		public boolean isDebugMode() {
			return mode == SigningMode.DEBUG;
		}

		public String getKeystorePath() {
			return keystorePath;
		}

		public String getKeyAlias() {
			return keyAlias;
		}

		public SigningScheme getScheme() {
			return scheme;
		}

		public String getV1SignerName() {
			return v1SignerName;
		}

		public String getStorePassword() {
			return storePassword;
		}

		public String getKeyPassword() {
			return keyPassword;
		}
	}
}
