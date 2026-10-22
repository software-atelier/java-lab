package ch.software_atelier.java_lab;

import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jdk.jshell.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;

@Service
public class KernelService implements AutoCloseable {
    private final Map<String, Kernel> kernels = new ConcurrentHashMap<>();
    private final KernelClasspath classpath = new KernelClasspath();
    private final MavenDependencies dependencies;
    private final NotebookStore notebooks;
    private final SqlKernelBridge sqlBridge;
    public KernelService() { this(null, new MavenDependencies()); }
    @org.springframework.beans.factory.annotation.Autowired
    public KernelService(NotebookStore notebooks, SqlService sql) { this(notebooks, new MavenDependencies(), sql); }
    public KernelService(NotebookStore notebooks) { this(notebooks, new MavenDependencies()); }
    KernelService(NotebookStore notebooks, MavenDependencies dependencies) {
        this(notebooks, dependencies, notebooks == null ? null : new SqlService(notebooks, new ObjectMapper()));
    }
    KernelService(NotebookStore notebooks, MavenDependencies dependencies, SqlService sql) {
        this.notebooks = notebooks;
        this.dependencies = dependencies;
        this.sqlBridge = new SqlKernelBridge(sql);
    }
    private final ScheduledExecutorService interrupts=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"java-lab-interrupt");t.setDaemon(true);return t;});
    public record Result(int executionCount, List<Map<String, Object>> outputs, boolean success) {}
    public record Suggestion(String text, boolean matchesType) {}
    public record Completions(int anchor, List<Suggestion> suggestions) {}
    public record Inspection(List<String> signatures) {}
    public record Resources(boolean available, long pid, long sampledAt, long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes, Double cpuPercent) {}

    /** Reads a background sample without evaluating snippets or extending the session's idle lifetime. */
    public Resources resources(String id) { return get(id).resources(); }

    public String create() {
        if (kernels.size() >= 32) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many kernels; close unused notebooks");
        String id = UUID.randomUUID().toString();
        kernels.put(id, new Kernel(classpath.resolve(), Map.of(), Set.of(), dataDirectory(), sqlBridge.endpoint()));
        return id;
    }

    public String create(String notebook) throws IOException {
        if (notebook == null || notebook.isBlank()) return create();
        if (notebooks == null) throw new IllegalStateException("Notebook store unavailable");
        if (kernels.size() >= 32) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many kernels; close unused notebooks");
        var settings = notebooks.settings(notebook);
        var values = notebooks.effectiveValues(notebook);
        List<Path> jars = dependencies.resolve(settings.dependencies());
        String cp = classpath.resolve() + jars.stream().map(path -> File.pathSeparator + path).collect(java.util.stream.Collectors.joining());
        Set<String> secrets = settings.variables().stream().filter(NotebookSettings.Variable::secret).map(v -> values.get(v.name())).filter(v -> v != null && !v.isEmpty()).collect(java.util.stream.Collectors.toSet());
        Kernel kernel = new Kernel(cp, values, secrets, dataDirectory(), sqlBridge.endpoint());
        kernel.dependencies = List.copyOf(settings.dependencies());
        String id = UUID.randomUUID().toString();
        kernels.put(id, kernel);
        return id;
    }

    private Path dataDirectory() { return Path.of(notebooks == null ? System.getProperty("user.dir") : notebooks.directory()).toAbsolutePath(); }

    public Result execute(String id, String code) {
        return execute(id, code, null);
    }

    public Result execute(String id, String code, Map<String, Object> formValues) {
        Kernel kernel = get(id);
        if (code == null || code.length() > 100_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cell source is missing or too large");
        String encodedValues = null;
        if (formValues != null) {
            if (formValues.size() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many form values");
            for (var entry : formValues.entrySet()) {
                if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z][A-Za-z0-9_.-]{0,99}")
                    || !(entry.getValue() == null || entry.getValue() instanceof String || entry.getValue() instanceof Boolean || entry.getValue() instanceof Number
                        || entry.getValue() instanceof List<?> items && items.size() <= 100 && items.stream().allMatch(String.class::isInstance)))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid form value");
            }
            try {
                String json = new ObjectMapper().writeValueAsString(formValues);
                if (json.length() > 100_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Form values are too large");
                encodedValues = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid form values");
            }
        }
        synchronized(kernel) {
            if (!kernel.busy.compareAndSet(false, true)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Kernel is already running");
            kernel.generation++;
        }
        kernel.lastUsed = System.currentTimeMillis();
        final String values = encodedValues;
        Future<Result> task = kernel.executor.submit(() -> kernel.evaluate(code, values));
        try { return task.get(30, TimeUnit.SECONDS); }
        catch (TimeoutException e) {
            kernel.shell.stop();
            task.cancel(true);
            return new Result(kernel.count, List.of(error("Timeout", "Execution exceeded 30 seconds. Restart the kernel if needed.")), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Execution interrupted");
        } catch (ExecutionException e) {
            return new Result(kernel.count, List.of(error("KernelError", Objects.toString(e.getCause().getMessage(), "Kernel terminated; restart it."))), false);
        } finally { kernel.busy.set(false); kernel.lastUsed = System.currentTimeMillis(); }
    }

    public void interrupt(String id) {
        Kernel kernel=get(id);long generation;boolean running;
        synchronized(kernel){generation=kernel.generation;running=kernel.busy.get();if(kernel.closed)return;kernel.shell.stop();}
        // JShell.stop() is a no-op during compilation. Retry until remote code starts or exits.
        retryInterrupt(kernel,generation,running,5);
    }
    private void retryInterrupt(Kernel kernel,long generation,boolean seenRunning,int idleAttempts) {
        interrupts.schedule(()->{
            if(kernel.closed)return;
            long current;boolean running;synchronized(kernel){current=kernel.generation;running=kernel.busy.get();}
            if(current!=generation) {
                if(!seenRunning&&current==generation+1){generationInterrupt(kernel,current,running,idleAttempts);}
                return;
            }
            generationInterrupt(kernel,generation,seenRunning||running,idleAttempts);
        },100,TimeUnit.MILLISECONDS);
    }
    private void generationInterrupt(Kernel kernel,long generation,boolean seenRunning,int idleAttempts) {
        boolean running;
        synchronized(kernel) {
            if(kernel.closed||kernel.generation!=generation)return;
            running=kernel.busy.get();
            if(running)try {kernel.shell.stop();}catch(IllegalStateException e){return;}
        }
        if(running)retryInterrupt(kernel,generation,true,idleAttempts);
        else if(!seenRunning&&idleAttempts>0)retryInterrupt(kernel,generation,false,idleAttempts-1);
    }

    public Completions complete(String id, String code, int cursor) {
        Kernel kernel = get(id);
        if (code == null || code.length() > 100_000 || cursor < 0 || cursor > code.length())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid completion source or cursor");
        if (kernel.busy.get()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Completion is available when the kernel is idle");
        kernel.lastUsed = System.currentTimeMillis();
        // Serialize analysis with evaluation: JShell's analysis API is not thread-safe.
        Future<Completions> task = kernel.executor.submit(() -> kernel.complete(code, cursor));
        try { return task.get(5, TimeUnit.SECONDS); }
        catch (TimeoutException e) {
            task.cancel(false);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Completion timed out; try again");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Completion interrupted");
        } catch (ExecutionException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kernel unavailable; restart it to restore completion");
        }
    }
    public void remove(String id) { Kernel kernel = kernels.remove(id); if (kernel != null) kernel.close(); }
    public Inspection inspect(String id,String code,int cursor) {
        Kernel kernel=get(id);
        if(code==null||code.length()>100_000||cursor<0||cursor>code.length())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid inspection source or cursor");
        if(kernel.busy.get())throw new ResponseStatusException(HttpStatus.CONFLICT,"Inspection is available when the kernel is idle");
        kernel.lastUsed=System.currentTimeMillis();
        Future<Inspection> task=kernel.executor.submit(()->kernel.inspect(code,cursor));
        try{return task.get(5,TimeUnit.SECONDS);}
        catch(TimeoutException e){task.cancel(false);throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Inspection timed out; try again");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Inspection interrupted");}
        catch(ExecutionException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Kernel unavailable; restart it to restore inspection");}
    }

    /** Structured AI inspection, serialized with all other work in this notebook's JVM. */
    public Object inspectSession(String id, String operation, com.fasterxml.jackson.databind.JsonNode arguments) {
        Kernel kernel = get(id);
        synchronized (kernel) {
            if (kernel.closed || !kernel.busy.compareAndSet(false, true))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Session inspection is available when the kernel is idle");
            kernel.generation++;
        }
        kernel.lastUsed = System.currentTimeMillis();
        Future<Object> task = kernel.executor.submit(() -> {
            try { return kernel.redact(kernel.inspectSession(operation, arguments)); }
            finally { kernel.busy.set(false); kernel.lastUsed = System.currentTimeMillis(); }
        });
        try { return task.get(5, TimeUnit.SECONDS); }
        catch (TimeoutException e) {
            interrupt(id);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Session inspection timed out");
        } catch (InterruptedException e) {
            interrupt(id);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Session inspection interrupted");
        } catch (ExecutionException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session inspection failed; check the variable, path, or class name");
        }
    }
    private Kernel get(String id) {
        Kernel kernel = kernels.get(id);
        if (kernel == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kernel expired. Restart the kernel to continue.");
        return kernel;
    }

    @Scheduled(fixedDelay = 60_000)
    public void expire() {
        long cutoff = System.currentTimeMillis() - Duration.ofMinutes(60).toMillis();
        kernels.forEach((id, kernel) -> { if (!kernel.busy.get() && kernel.lastUsed < cutoff) remove(id); });
    }
    @PreDestroy public void close() { kernels.keySet().forEach(this::remove); interrupts.shutdownNow();sqlBridge.close();classpath.close(); }

    private static Map<String, Object> error(String name, String message) {
        return Map.of("output_type", "error", "ename", name, "evalue", message, "traceback", List.of(name + ": " + message));
    }

    private static final class Kernel {
        private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        final ByteArrayOutputStream stdout = new LimitedOutput();
        final ByteArrayOutputStream stderr = new LimitedOutput();
        final JShell shell;
        final String classpath;
        List<String> dependencies = List.of();
        List<Map<String,String>> libraryClasses;
        List<Map<String,Object>> libraries;
        final List<String> secretValues;
        final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "java-lab-kernel"); t.setDaemon(true); return t; });
        final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
        volatile long lastUsed = System.currentTimeMillis();
        volatile int count;
        volatile long generation;
        volatile boolean closed;
        Path resourceDirectory;
        final Object resourceLock = new Object();
        long previousResourceTime, previousCpuNanos = -1;
        Double cpuPercent;

        Kernel(String classpath, Map<String,String> variables, Set<String> secrets, Path dataDirectory, String sqlEndpoint) {
            this.classpath = classpath;
            secretValues = secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
            shell = JShell.builder().out(new PrintStream(stdout, true, StandardCharsets.UTF_8))
                .err(new PrintStream(stderr, true, StandardCharsets.UTF_8))
                .remoteVMOptions("-Djava.awt.headless=true", "-Djava.lab.sql.endpoint=" + sqlEndpoint, "\"-Duser.dir=" + dataDirectory.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"").build();
            shell.addToClasspath(classpath);
            shell.eval("import java.util.*;");
            shell.eval("import java.util.stream.*;");
            shell.eval("import java.time.*;");
            shell.eval("import java.nio.file.*;");
            shell.eval("import org.jfree.chart.*;");
            shell.eval("import org.jfree.data.category.*;");
            shell.eval("import org.jfree.data.xy.*;");
            shell.eval("import org.jfree.data.general.*;");
            shell.eval("import org.jfree.data.time.*;");
            shell.eval("import ch.software_atelier.java_lab.display.Charts;");
            shell.eval("import ch.software_atelier.java_lab.display.ECharts;");
            shell.eval("import ch.software_atelier.java_lab.display.Grid;");
            shell.eval("import ch.software_atelier.java_lab.display.Formatted;");
            shell.eval("import ch.software_atelier.java_lab.display.Forms;");
            shell.eval("import ch.software_atelier.java_lab.display.Images;");
            shell.eval("import org.json.*;");
            shell.eval("import org.apache.commons.csv.*;");
            shell.eval("import ch.software_atelier.java_lab.data.Csv;");
            shell.eval("import ch.software_atelier.java_lab.data.Sql;");
            shell.eval("import org.apache.commons.io.input.BOMInputStream;");
            shell.eval("void log(String msg, Object... args) { ch.software_atelier.java_lab.display.Logs.log(msg, args); }");
            shell.eval("java.util.Map<String,String> __labVars = new java.util.HashMap<>();");
            for (var entry : variables.entrySet()) {
                String name = Base64.getEncoder().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
                String value = Base64.getEncoder().encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8));
                var init = shell.eval("__labVars.put(new String(java.util.Base64.getDecoder().decode(\"" + name + "\"), java.nio.charset.StandardCharsets.UTF_8), new String(java.util.Base64.getDecoder().decode(\"" + value + "\"), java.nio.charset.StandardCharsets.UTF_8));");
                if (init.stream().anyMatch(e -> e.status() == Snippet.Status.REJECTED || e.exception() != null)) throw new IllegalStateException("Could not initialize notebook variables");
            }
            shell.eval("String labVar(String name) { return __labVars.get(name); }");
            shell.eval("String requireLabVar(String name) { String value = __labVars.get(name); if (value == null) throw new IllegalArgumentException(\"Missing notebook variable: \" + name); return value; }");
            try {
                resourceDirectory = java.nio.file.Files.createTempDirectory("java-lab-resources-");
                String encoded = Base64.getEncoder().encodeToString(resourceDirectory.toString().getBytes(StandardCharsets.UTF_8));
                shell.eval("ch.software_atelier.java_lab.KernelResourceSampler.start(\"" + encoded + "\");");
            } catch (IOException ignored) { /* Resource monitoring is optional. */ }
        }

        Resources resources() {
            synchronized (resourceLock) {
                if (!closed && resourceDirectory != null) {
                    try (var input = new DataInputStream(java.nio.file.Files.newInputStream(resourceDirectory.resolve("sample")))) {
                        long pid=input.readLong(), time=input.readLong(), used=input.readLong(), committed=input.readLong(), max=input.readLong(), cpu=input.readLong();
                        if (System.currentTimeMillis()-time <= 10_000 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                            if (time > previousResourceTime) {
                                cpuPercent = previousResourceTime > 0 && cpu >= previousCpuNanos && previousCpuNanos >= 0
                                    ? 100.0 * (cpu-previousCpuNanos) / ((time-previousResourceTime)*1_000_000.0) : null;
                                previousResourceTime=time;previousCpuNanos=cpu;
                            }
                            return new Resources(true,pid,time,used,committed,max,cpuPercent);
                        }
                    } catch (IOException | RuntimeException ignored) { /* Missing, incomplete, or expired sample. */ }
                }
                return new Resources(false,0,0,0,0,0,null);
            }
        }

        Object inspectSession(String operation, com.fasterxml.jackson.databind.JsonNode args) throws IOException {
            return switch (operation) {
                case "session_overview" -> {
                    indexLibraries();
                    int offset = Math.max(0, args.path("offset").asInt());
                    int limit = Math.max(1, Math.min(50, args.path("limit").asInt(50)));
                    var variables = shell.variables().filter(v -> visible(v.name()) && shell.status(v).isDefined())
                        .map(v -> Map.of("name", v.name(), "type", v.typeName())).toList();
                    var imports = shell.imports().filter(i -> shell.status(i).isActive()).map(ImportSnippet::source).toList();
                    var methods = shell.methods().filter(m -> visible(m.name()) && shell.status(m).isDefined())
                        .map(m -> Map.of("name", m.name(), "signature", m.signature())).toList();
                    var types = shell.types().filter(t -> shell.status(t).isDefined()).map(TypeDeclSnippet::name).toList();
                    yield Map.of("variables", page(variables, offset, limit), "imports", page(imports, offset, limit),
                        "methods", page(methods, offset, limit), "types", page(types, offset, limit),
                        "libraries", page(libraries, offset, limit), "dependencies", dependencies,
                        "totals", Map.of("variables", variables.size(), "imports", imports.size(), "methods", methods.size(), "types", types.size(), "libraries", libraries.size()),
                        "offset", offset, "limit", limit,
                        "note", "Metadata only. Paginate each category using offset and limit. Use inspect_variable to read current values without printing them. Paths use selectors returned by that tool. No arbitrary getters or expressions are evaluated.");
                }
                case "inspect_variable" -> {
                    String name = args.path("variable").asText();
                    var variable = shell.variables().filter(v -> visible(v.name()) && v.name().equals(name) && shell.status(v).isDefined()).findFirst();
                    if (variable.isEmpty()) yield Map.of("error", "Variable is not defined in the current session");
                    yield remoteInspection("inspect(" + variable.get().name() + ",", args);
                }
                case "search_library_classes" -> {
                    indexLibraries();
                    String query = args.path("query").asText().toLowerCase(Locale.ROOT);
                    int offset = Math.max(0, args.path("offset").asInt());
                    var matches = libraryClasses.stream().filter(c -> c.get("className").toLowerCase(Locale.ROOT).contains(query)).toList();
                    yield Map.of("classes", matches.stream().skip(offset).limit(40).toList(), "total", matches.size(),
                        "hasMore", (long) offset + 40 < matches.size());
                }
                case "inspect_class" -> {
                    var localType = shell.types().filter(t -> t.name().equals(args.path("className").asText()) && shell.status(t).isDefined()).findFirst();
                    yield remoteInspection(localType.map(t -> "api(" + t.name() + ".class,").orElse("api("), args);
                }
                default -> Map.of("error", "Unknown inspection tool");
            };
        }

        private boolean visible(String name) { return !name.equals("__labVars") && !name.matches("\\$\\d+"); }

        private <T> List<T> page(List<T> values, int offset, int limit) { return values.stream().skip(offset).limit(limit).toList(); }

        private Object remoteInspection(String invocation, com.fasterxml.jackson.databind.JsonNode args) throws IOException {
            var request = (com.fasterxml.jackson.databind.node.ObjectNode) args.deepCopy();
            request.set("__secretValues", JSON.valueToTree(secretValues));
            String encoded = Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(request));
            var events = shell.eval("ch.software_atelier.java_lab.display.SessionInspector." + invocation + "\"" + encoded + "\")");
            try {
                for (var event : events) {
                    if (event.exception() != null || event.status() == Snippet.Status.REJECTED) throw new IOException("Inspection failed");
                    String value = event.value();
                    if (value != null && value.startsWith("\"") && value.endsWith("\"")) {
                        byte[] bytes = Base64.getDecoder().decode(value.substring(1, value.length() - 1));
                        return JSON.readValue(bytes, Object.class);
                    }
                }
                throw new IOException("No inspection result");
            } finally {
                for (var event : events) if (event.snippet() instanceof VarSnippet) shell.drop(event.snippet());
                stdout.reset(); stderr.reset();
            }
        }

        private void indexLibraries() throws IOException {
            if (libraryClasses != null) return;
            var classes = new ArrayList<Map<String,String>>();
            var jars = new ArrayList<Map<String,Object>>();
            for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                Path path = Path.of(entry);
                if (!entry.endsWith(".jar")) continue;
                try (var jar = new JarFile(path.toFile())) {
                    var coordinates = new ArrayList<String>();
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        var item = entries.nextElement();
                        String name = item.getName();
                        if (name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                            var properties = new Properties();
                            try (var input = jar.getInputStream(item)) { properties.load(input); }
                            coordinates.add(properties.getProperty("groupId", "") + ":" + properties.getProperty("artifactId", "") + ":" + properties.getProperty("version", ""));
                        }
                        if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class") && !name.endsWith("package-info.class"))
                            classes.add(Map.of("className", name.substring(0, name.length() - 6).replace('/', '.'), "library", path.getFileName().toString()));
                    }
                    var manifest = jar.getManifest();
                    String version = manifest == null ? "" : Objects.toString(manifest.getMainAttributes().getValue("Implementation-Version"), "");
                    jars.add(Map.of("jar", path.getFileName().toString(), "coordinates", coordinates, "version", version));
                }
            }
            classes.sort(Comparator.comparing(c -> c.get("className")));
            libraries = List.copyOf(jars);
            libraryClasses = List.copyOf(classes);
        }

        Completions complete(String code, int cursor) {
            SourceCodeAnalysis analysis = shell.sourceCodeAnalysis();
            int offset=snippetOffset(code,cursor);
            int[] anchor = new int[1];
            List<Suggestion> suggestions = analysis.completionSuggestions(code.substring(offset), cursor - offset, anchor)
                .stream().map(s -> new Suggestion(s.continuation(), s.matchesType())).distinct()
                .sorted(Comparator.comparing(Suggestion::matchesType).reversed().thenComparing(Suggestion::text))
                .limit(100).toList();
            return new Completions(offset + anchor[0], suggestions);
        }

        Inspection inspect(String code,int cursor) {
            int offset=snippetOffset(code,cursor);
            return new Inspection(shell.sourceCodeAnalysis().documentation(code.substring(offset),cursor-offset,false)
                .stream().map(SourceCodeAnalysis.Documentation::signature).distinct().limit(20).toList());
        }

        private int snippetOffset(String code,int cursor) {
            SourceCodeAnalysis analysis = shell.sourceCodeAnalysis();
            String prefix = code.substring(0, cursor);
            int offset = 0;
            // Cells can contain several snippets; analyze the snippet at the caret.
            while (!prefix.isBlank()) {
                var info = analysis.analyzeCompletion(prefix);
                if (!info.completeness().isComplete() || info.remaining().isEmpty() || info.remaining().equals(prefix)) break;
                offset += prefix.length() - info.remaining().length();
                prefix = info.remaining();
            }
            return offset;
        }

        Result evaluate(String code, String encodedValues) {
            count++;
            stdout.reset(); stderr.reset();
            List<Map<String, Object>> outputs = new ArrayList<>();
            if (encodedValues != null) {
                var sync = shell.eval("ch.software_atelier.java_lab.display.Forms.loadBase64(\"" + encodedValues + "\");");
                if (sync.stream().anyMatch(event -> event.exception() != null || event.status() == Snippet.Status.REJECTED)) {
                    outputs.add(error("FormError", "Could not synchronize form values"));
                    return new Result(count, outputs, false);
                }
            }
            String remaining = code;
            boolean success = true;
            while (!remaining.isBlank()) {
                SourceCodeAnalysis.CompletionInfo info = shell.sourceCodeAnalysis().analyzeCompletion(remaining);
                if (!info.completeness().isComplete()) {
                    outputs.add(error("IncompleteInput", "Incomplete Java snippet. Check braces, parentheses, and semicolons."));
                    success = false; break;
                }
                var events = shell.eval(info.source());
                boolean last = info.remaining().isBlank()
                    || shell.sourceCodeAnalysis().analyzeCompletion(info.remaining()).completeness() == SourceCodeAnalysis.Completeness.EMPTY;
                drain(outputs);
                for (SnippetEvent event : events) {
                    if (event.causeSnippet() != null) continue;
                    if (event.status() == Snippet.Status.REJECTED || event.status() == Snippet.Status.RECOVERABLE_NOT_DEFINED) {
                        String diagnostics = shell.diagnostics(event.snippet()).map(d -> d.getMessage(Locale.ENGLISH)).reduce((a, b) -> a + "\n" + b).orElse("Snippet could not be compiled");
                        outputs.add(error("CompilationError", diagnostics)); success = false;
                    } else if (event.exception() != null) {
                        JShellException exception = event.exception();
                        String name = exception instanceof EvalException eval ? eval.getExceptionClassName() : exception.getClass().getSimpleName();
                        outputs.add(error(name, Objects.toString(exception.getMessage(), "Execution failed"))); success = false;
                    } else if (last && event.value() != null && !event.value().equals("null") && isDisplayValue(event.snippet())) {
                        String name = event.snippet() instanceof VarSnippet variable ? variable.name() : ((ExpressionSnippet) event.snippet()).name();
                        String type = snippetType(event.snippet());
                        String helper = isGridType(type) ? "Grid" : isEChartsType(type) ? "ECharts" : "Charts";
                        for (var rendered : shell.eval("ch.software_atelier.java_lab.display." + helper + ".show(" + name + ");")) {
                            if (rendered.exception() != null) {
                                outputs.add(error("ChartError", Objects.toString(rendered.exception().getMessage(), "Could not render chart")));
                                success = false;
                            }
                        }
                    } else if(last && event.value()!=null && !event.value().isEmpty()
                        && event.snippet().kind()!=Snippet.Kind.IMPORT && !isPrintStream(event.snippet())) {
                        outputs.add(Map.of("output_type","execute_result","execution_count",count,"metadata",Map.of(),"data",Map.of("text/plain",event.value())));
                    }
                }
                if (!drainCharts(outputs)) success = false;
                if (!success) break;
                if (info.remaining().equals(remaining)) break;
                remaining = info.remaining();
            }
            drain(outputs);
            return new Result(count, outputs.stream().map(this::redactOutput).toList(), success);
        }

        @SuppressWarnings("unchecked")
        private Map<String,Object> redactOutput(Map<String,Object> output) {
            return (Map<String,Object>) redact(output);
        }
        private Object redact(Object value) {
            if (value instanceof String text) {
                for (String secret : secretValues) text = text.replace(secret, "[REDACTED]");
                return text;
            }
            if (value instanceof Map<?,?> map) {
                Map<String,Object> result = new LinkedHashMap<>();
                map.forEach((key, val) -> result.put((String) key, redact(val)));
                return result;
            }
            if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
            return value;
        }

        private boolean isDisplayValue(Snippet snippet) {
            return snippet instanceof VarSnippet variable
                    && (isChartType(variable.typeName()) || isEChartsType(variable.typeName()) || isGridType(variable.typeName()))
                || snippet instanceof ExpressionSnippet expression
                    && (isChartType(expression.typeName()) || isEChartsType(expression.typeName()) || isGridType(expression.typeName()));
        }

        private boolean isPrintStream(Snippet snippet) {
            String type=snippet instanceof VarSnippet v?v.typeName():snippet instanceof ExpressionSnippet e?e.typeName():"";
            return type.equals("java.io.PrintStream")||type.equals("PrintStream");
        }

        private boolean isChartType(String type) { return type.equals("JFreeChart") || type.equals("org.jfree.chart.JFreeChart"); }
        private boolean isEChartsType(String type) { return type.equals("ECharts.Chart") || type.equals("ch.software_atelier.java_lab.display.ECharts.Chart"); }
        private boolean isGridType(String type) { return type.equals("Grid.Table") || type.equals("ch.software_atelier.java_lab.display.Grid.Table"); }
        private String snippetType(Snippet snippet) { return snippet instanceof VarSnippet v ? v.typeName() : ((ExpressionSnippet) snippet).typeName(); }

        private boolean drainCharts(List<Map<String, Object>> outputs) {
            try {
                String chunk = chartChunk("drain");
                if (chunk.isEmpty()) return true;
                var encoded = new StringBuilder();
                while (!chunk.isEmpty()) {
                    encoded.append(chunk);
                    if (encoded.length() > 22_000_000) throw new IOException("Chart transport exceeds its limit");
                    chunk = chartChunk("nextChunk");
                }
                byte[] bytes = Base64.getDecoder().decode(encoded.toString());
                try (var data = new DataInputStream(new ByteArrayInputStream(bytes))) {
                    int images = data.readInt();
                    if (images < 0 || images > 16) throw new IOException("Invalid chart count");
                    for (int i = 0; i < images; i++) {
                        String mime = data.readUTF();
                        String title = data.readUTF();
                        int width = data.readInt(), height = data.readInt(), size = data.readInt();
                        if (size < 0 || size > 16_000_000) throw new IOException("Invalid chart image size");
                        byte[] content = data.readNBytes(size);
                        if (content.length != size) throw new IOException("Incomplete chart output");
                        Object value;
                        if (Set.of("image/png","image/jpeg","image/gif","image/bmp","image/webp","image/svg+xml").contains(mime)) value = Base64.getEncoder().encodeToString(content);
                        else if (mime.equals("application/vnd.apache.echarts+json")) {
                            var option = JSON.readTree(content);
                            if (option == null || !option.isObject()) throw new IOException("ECharts option must be a JSON object");
                            var heading = option.path("title");
                            if (heading.isArray()) heading = heading.path(0);
                            title = heading.path("text").asText("Apache ECharts");
                            value = option;
                        } else if (mime.equals("application/vnd.gridjs+json")) {
                            var option = JSON.readTree(content);
                            if (option == null || !option.isObject() || !option.path("columns").isArray() || !option.path("data").isArray())
                                throw new IOException("Grid.js output requires column and data arrays");
                            value = option;title = option.path("title").asText("Grid.js table");
                        } else if (mime.equals("application/vnd.java-lab.code+json")) {
                            var option = JSON.readTree(content);
                            if (option == null || !option.isObject() || !option.path("format").isTextual() || !option.path("content").isTextual())
                                throw new IOException("Formatted output requires format and content strings");
                            value = option;
                        } else if (mime.equals("application/vnd.java-lab.form+json")) {
                            var option = JSON.readTree(content);
                            if (option == null || !option.isObject() || !option.path("id").isTextual() || !option.path("fields").isArray())
                                throw new IOException("Form output requires id and fields");
                            value = option;
                        } else throw new IOException("Unsupported display format");
                        outputs.add(Map.of("output_type", "display_data",
                            "data", Map.of(mime, value, "text/plain", title),
                            "metadata", Map.of(mime, Map.of("width", width, "height", height))));
                    }
                }
                return true;
            } catch (IOException | IllegalArgumentException e) {
                String detail = Objects.toString(e.getMessage(), "Unknown display error");
                outputs.add(error("ChartError", "Could not read display output: " + detail.substring(0, Math.min(detail.length(), 300))));return false;
            }
        }

        private String chartChunk(String method) throws IOException {
            var events = shell.eval("ch.software_atelier.java_lab.display.Display." + method + "()");
            try {
                for (var event : events) {
                    if (event.exception() != null || event.status() == Snippet.Status.REJECTED) throw new IOException("Chart transport failed");
                    String value = event.value();
                    if (value != null && value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1);
                }
                throw new IOException("Chart transport returned no data");
            } finally { for (var event : events) if (event.snippet() instanceof VarSnippet) shell.drop(event.snippet()); }
        }

        private void drain(List<Map<String, Object>> outputs) {
            for (var entry : Map.of("stdout", stdout, "stderr", stderr).entrySet()) {
                synchronized (entry.getValue()) {
                    if (entry.getValue().size() > 0) {
                        outputs.add(Map.of("output_type", "stream", "name", entry.getKey(), "text", entry.getValue().toString(StandardCharsets.UTF_8)));
                        entry.getValue().reset();
                    }
                }
            }
        }
        void close() {
            closed=true;
            try { shell.stop(); shell.close(); }
            finally {
                executor.shutdownNow();
                if(resourceDirectory!=null)try {
                    java.nio.file.Files.deleteIfExists(resourceDirectory.resolve("sample"));
                    java.nio.file.Files.deleteIfExists(resourceDirectory.resolve("sample.tmp"));
                    java.nio.file.Files.deleteIfExists(resourceDirectory);
                } catch(IOException ignored) { resourceDirectory.toFile().deleteOnExit(); }
            }
        }
    }

    private static final class LimitedOutput extends ByteArrayOutputStream {
        private static final int LIMIT = 1_000_000;
        @Override public synchronized void write(int b) { if (count < LIMIT) super.write(b); }
        @Override public synchronized void write(byte[] b, int off, int len) { super.write(b, off, Math.min(len, LIMIT - count)); }
    }
}
