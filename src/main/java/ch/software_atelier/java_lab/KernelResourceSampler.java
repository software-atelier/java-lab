package ch.software_atelier.java_lab;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Runs inside the notebook JVM, independently of the JShell execution thread. */
public final class KernelResourceSampler {
    private KernelResourceSampler() {}

    public static void start(String encodedDirectory) {
        Path directory = Path.of(new String(Base64.getDecoder().decode(encodedDirectory), java.nio.charset.StandardCharsets.UTF_8));
        Runnable sample = () -> {
            try {
                var runtime = Runtime.getRuntime();
                long committed = runtime.totalMemory();
                long used = Math.max(0, committed-runtime.freeMemory());
                var process = ProcessHandle.current();
                Path temporary = directory.resolve("sample.tmp");
                try (var output = new DataOutputStream(Files.newOutputStream(temporary))) {
                    output.writeLong(process.pid());
                    output.writeLong(System.currentTimeMillis());
                    output.writeLong(used);
                    output.writeLong(committed);
                    output.writeLong(runtime.maxMemory());
                    output.writeLong(process.info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L));
                }
                try { Files.move(temporary, directory.resolve("sample"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, directory.resolve("sample"), StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException | RuntimeException ignored) { /* Monitoring must never interrupt notebook execution. */ }
        };
        sample.run();
        var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "java-lab-resources");thread.setDaemon(true);return thread;
        });
        scheduler.scheduleWithFixedDelay(sample, 1, 1, TimeUnit.SECONDS);
    }
}
