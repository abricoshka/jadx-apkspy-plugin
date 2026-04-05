package jadx.plugins.apkspy;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class ApktoolWrapper {
	private static final long HEARTBEAT_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);
	private static final long INACTIVITY_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

	public static void decode(Path apk, String apktoolLocation, String jdkLocation, String dir, boolean resources, OutputStream out)
			throws InterruptedException, IOException {
		runCommand("decode " + dir, buildApktoolDecodeCommand(apk, apktoolLocation, jdkLocation, dir, resources),
				jdkLocation, out);
	}

	public static void build(Path apk, String apktoolLocation, String jdkLocation, String outputLocation, OutputStream out)
			throws InterruptedException, IOException {
		runCommand("build " + apk.getFileName(), buildApktoolBuildCommand(apk, apktoolLocation, jdkLocation, outputLocation),
				jdkLocation, out);
	}

	static List<String> buildApktoolDecodeCommand(Path apk, String apktoolLocation, String jdkLocation, String dir,
			boolean resources) {
		List<String> command = new ArrayList<>();
		command.add(resolveJavaExecutable(jdkLocation));
		command.add("-jar");
		command.add(apktoolLocation);
		command.add("decode");
		command.add("-o");
		command.add("smali" + File.separator + dir);
		if (!resources) {
			command.add("-r");
		}
		command.add(apk.toAbsolutePath().toString());
		return sanitizeCommand(command);
	}

	static List<String> buildApktoolBuildCommand(Path apk, String apktoolLocation, String jdkLocation, String outputLocation) {
		List<String> command = new ArrayList<>();
		command.add(resolveJavaExecutable(jdkLocation));
		command.add("-jar");
		command.add(apktoolLocation);
		command.add("build");
		command.add("-o");
		command.add(outputLocation);
		command.add(apk.toAbsolutePath().toString());
		return sanitizeCommand(command);
	}

	private static String resolveJavaExecutable(String jdkLocation) {
		if (jdkLocation == null || jdkLocation.isBlank()) {
			return "java";
		}
		return Path.of(jdkLocation, "bin", "java.exe").toFile().exists()
				? Path.of(jdkLocation, "bin", "java.exe").toString()
				: Path.of(jdkLocation, "bin", "java").toString();
	}

	private static List<String> sanitizeCommand(List<String> command) {
		return command.stream()
				.filter(arg -> arg != null && !arg.isBlank())
				.collect(Collectors.toList());
	}

	private static void runCommand(String stage, List<String> command, String jdkLocation, OutputStream out)
			throws IOException, InterruptedException {
		writeLine(out, "[ApkSpy] apktool stage start: " + stage);
		logCommand(command, out);

		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(new File(System.getProperty("user.dir")))
				.redirectErrorStream(true);
		if (jdkLocation != null && !jdkLocation.isBlank()) {
			builder.environment().put("JAVA_HOME", jdkLocation);
		}

		Process process = builder.start();
		writeLine(out, "[ApkSpy] apktool process started: pid=" + process.pid());

		AtomicLong lastOutputAt = new AtomicLong(System.nanoTime());
		Thread outputThread = new Thread(() -> drainOutput(process, out, lastOutputAt), "apktool-" + stage);
		outputThread.setDaemon(true);
		outputThread.start();

		long startedAt = System.nanoTime();
		while (!process.waitFor(HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
			long now = System.nanoTime();
			long idleMs = TimeUnit.NANOSECONDS.toMillis(now - lastOutputAt.get());
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - startedAt);
			writeLine(out, "[ApkSpy] apktool heartbeat [" + stage + "]: elapsed=" + elapsedMs + "ms, idle=" + idleMs
					+ "ms");
			if (idleMs >= INACTIVITY_TIMEOUT_MS) {
				writeLine(out, "[ApkSpy] apktool inactivity timeout [" + stage + "] after " + idleMs + "ms");
				process.destroy();
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					process.destroyForcibly();
					process.waitFor(5, TimeUnit.SECONDS);
				}
				outputThread.join(TimeUnit.SECONDS.toMillis(5));
				throw new IOException("apktool stage '" + stage + "' timed out after " + idleMs + "ms without output");
			}
		}
		outputThread.join(TimeUnit.SECONDS.toMillis(5));
		writeLine(out, "[ApkSpy] apktool stage finished: " + stage + ", exitCode=" + process.exitValue());
		if (process.waitFor() != 0) {
			throw new IOException("apktool command failed with exit code " + process.exitValue());
		}
	}

	private static void drainOutput(Process process, OutputStream out, AtomicLong lastOutputAt) {
		byte[] buffer = new byte[8192];
		try {
			int read;
			while ((read = process.getInputStream().read(buffer)) != -1) {
				lastOutputAt.set(System.nanoTime());
				synchronized (out) {
					out.write(buffer, 0, read);
					out.flush();
				}
			}
		} catch (IOException ignore) {
			// process is terminating; stage summary is logged by caller
		}
	}

	private static void logCommand(List<String> command, OutputStream out) throws IOException {
		writeLine(out, "[ApkSpy] apktool args:");
		for (int i = 0; i < command.size(); i++) {
			writeLine(out, "[ApkSpy]   [" + i + "] " + command.get(i));
		}
	}

	private static void writeLine(OutputStream out, String message) throws IOException {
		synchronized (out) {
			out.write(message.getBytes(StandardCharsets.UTF_8));
			out.write('\n');
			out.flush();
		}
	}
}
