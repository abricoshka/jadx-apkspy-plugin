package jadx.plugins.apkspy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CompileSourceNormalizerTest {

	@Test
	void rewritesRawEntrySetForeachLoopToObjectLoopAndCast() {
		String source = "package test;\n"
				+ "\n"
				+ "import java.util.HashMap;\n"
				+ "import java.util.Map;\n"
				+ "\n"
				+ "public class Demo {\n"
				+ "    public void run(HashMap values) {\n"
				+ "        for (Map.Entry entry : ((HashMap) values).entrySet()) {\n"
				+ "            System.out.println(entry.getKey());\n"
				+ "        }\n"
				+ "    }\n"
				+ "}\n";

		CompileSourceNormalizer.NormalizedSource normalized = CompileSourceNormalizer.normalize("test.Demo", source);

		assertThat(normalized.getSource()).contains("for (Object apkSpyEntryObj0 : ((HashMap) values).entrySet())");
		assertThat(normalized.getSource()).contains("Map.Entry entry = (Map.Entry) apkSpyEntryObj0;");
		assertThat(normalized.getDiagnostics()).anySatisfy(diagnostic -> assertThat(diagnostic).contains("entrySet()"));
	}

	@Test
	void insertsOrientationEventListenerSuperCall() {
		String source = "package test;\n"
				+ "\n"
				+ "import android.content.Context;\n"
				+ "import android.view.OrientationEventListener;\n"
				+ "\n"
				+ "public class Demo {\n"
				+ "    class Listener extends OrientationEventListener {\n"
				+ "        Listener(Context context, int rate) {\n"
				+ "            this.toString();\n"
				+ "        }\n"
				+ "    }\n"
				+ "}\n";

		CompileSourceNormalizer.NormalizedSource normalized = CompileSourceNormalizer.normalize("test.Demo", source);

		assertThat(normalized.getSource()).contains("Listener(Context context, int rate) {");
		assertThat(normalized.getSource()).contains("super(context, rate);");
		assertThat(normalized.getDiagnostics()).anySatisfy(diagnostic -> assertThat(diagnostic).contains("OrientationEventListener"));
	}

	@Test
	void addsSyntheticInnerFieldStubForSwitchMapStyleAccess() {
		String source = "package test;\n"
				+ "\n"
				+ "public class Demo {\n"
				+ "    static final class c {\n"
				+ "    }\n"
				+ "\n"
				+ "    public int read(int index) {\n"
				+ "        return c.f4544a[index];\n"
				+ "    }\n"
				+ "}\n";

		CompileSourceNormalizer.NormalizedSource normalized = CompileSourceNormalizer.normalize("test.Demo", source);

		assertThat(normalized.getSource()).contains("static int[] f4544a = new int[0];");
		assertThat(normalized.getDiagnostics()).anySatisfy(diagnostic -> assertThat(diagnostic).contains("c.f4544a"));
	}

	@Test
	void collectsRReferencesAndRendersCompileTimeStub() {
		String source = "package test;\n"
				+ "\n"
				+ "public class Demo {\n"
				+ "    public int read() {\n"
				+ "        return R.layout.main + R.id.button + R.string.title;\n"
				+ "    }\n"
				+ "}\n";

		CompileSourceNormalizer.NormalizedSource normalized = CompileSourceNormalizer.normalize("test.Demo", source);
		Map<String, Set<String>> references = normalized.getRReferences();
		String rSource = CompileSourceNormalizer.renderRSource("test", references);

		assertThat(references).containsEntry("layout", Set.of("main"));
		assertThat(references).containsEntry("id", Set.of("button"));
		assertThat(references).containsEntry("string", Set.of("title"));
		assertThat(rSource).contains("package test;");
		assertThat(rSource).contains("public static final class layout");
		assertThat(rSource).contains("public static final int main = 0;");
		assertThat(rSource).contains("public static final class id");
		assertThat(rSource).contains("public static final int button = 0;");
	}
}
