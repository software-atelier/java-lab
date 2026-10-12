package dev.mars.lab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Remote JShell needs real filesystem classpath entries, rather than Spring Boot nested URLs. */
final class KernelClasspath implements AutoCloseable {
    private Path directory;
    private static final List<String> LIBRARIES=List.of("jfreechart","json","commons-csv","commons-io","commons-codec");

    synchronized String resolve() {
        try {
            if (directory == null) {
                directory = Files.createTempDirectory("java-lab-kernel-");
                for(String library:LIBRARIES)copy("kernel-runtime/"+library+".jar",directory.resolve(library+".jar"));
                for (Class<?> helper : new Class<?>[]{dev.mars.lab.display.Charts.class, dev.mars.lab.display.Display.class, dev.mars.lab.display.ECharts.class, dev.mars.lab.display.Logs.class}) copyClass(helper);
                copyClass(dev.mars.lab.display.Grid.class);
                copy("dev/mars/lab/display/JsonOptions.class", directory.resolve("classes/dev/mars/lab/display/JsonOptions.class"));
            }
            return directory.resolve("classes") + File.pathSeparator + LIBRARIES.stream().map(name->directory.resolve(name+".jar").toString()).collect(Collectors.joining(File.pathSeparator));
        } catch (IOException e) {
            close();
            throw new IllegalStateException("Could not unpack bundled Java notebook libraries", e);
        }
    }

    private void copy(String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (var input = KernelClasspath.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing bundled resource: " + resource);
            Files.copy(input, target);
        }
    }

    private void copyClass(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        copy(resource, directory.resolve("classes/" + resource));
        for (Class<?> nested : type.getDeclaredClasses()) copyClass(nested);
    }

    @Override public synchronized void close() {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            directory = null;
        } catch (IOException e) { directory.toFile().deleteOnExit(); }
    }
}
