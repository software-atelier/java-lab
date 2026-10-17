package ch.software_atelier.java_lab.ai;

import jdk.jshell.JShell;
import jdk.jshell.SourceCodeAnalysis;
import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Parses notebook snippets without evaluating, compiling, or changing the user's live kernel. */
final class JavaSnippetValidator {
    private JavaSnippetValidator() {}

    /** Null means structurally complete. This does not resolve types or validate runtime semantics. */
    static String problem(CodeGeneration.Target target, String replacement) {
        return problem(target, replacement, () -> {});
    }

    static String problem(CodeGeneration.Target target, String replacement, Runnable checkDeadline) {
        if (replacement == null || replacement.isBlank()) return "The replacement is empty.";
        String source = target == null || target.start() == null ? replacement
            : target.source().substring(0, target.start()) + replacement + target.source().substring(target.end());
        if (source.length() > 100000) return "The resulting cell exceeds the 100,000-character limit.";
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var shell = JShell.builder().executionEngine("local").build();
             var files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            SourceCodeAnalysis analysis = shell.sourceCodeAnalysis();
            String remaining = source;
            while (!remaining.isBlank()) {
                checkDeadline.run();
                var info = analysis.analyzeCompletion(remaining);
                if (info.completeness() == SourceCodeAnalysis.Completeness.EMPTY) return null;
                int offset = source.length() - remaining.length();
                if (!info.completeness().isComplete())
                    return "The resulting cell contains an incomplete or malformed Java snippet near character " + offset + ".";
                if (!syntaxValid(compiler, files, "class AiCheck { void check() {\n" + info.source() + "\n} }")
                    && !syntaxValid(compiler, files, "class AiCheck {\n" + info.source() + "\n}")
                    && !syntaxValid(compiler, files, info.source())
                    && !syntaxValid(compiler, files, "class AiCheck { void check() { var result =\n" + info.source() + "\n; } }"))
                    return "The resulting cell contains malformed Java syntax near character " + offset + ".";
                if (remaining.equals(info.remaining())) return "The Java snippet could not be parsed completely.";
                remaining = info.remaining();
            }
            return null;
        } catch (IOException e) { throw new IllegalStateException("Could not initialize the Java syntax parser", e); }
    }

    private static boolean syntaxValid(JavaCompiler compiler, StandardJavaFileManager files, String source) throws IOException {
        var errors = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///AiCheck.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        var task = (JavacTask) compiler.getTask(null, files, errors, List.of("-proc:none"), null, List.of(file));
        task.parse(); // No analyze(), generate(), annotation processors, or snippet evaluation.
        return errors.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }
}
