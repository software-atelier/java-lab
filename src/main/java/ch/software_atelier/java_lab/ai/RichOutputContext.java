package ch.software_atelier.java_lab.ai;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** API guidance for generated notebook cells using rich text and input forms. */
@Component
public class RichOutputContext implements AiContextProvider {
    @Override public List<CodeGeneration.ContextItem> contextFor(CodeGeneration.Request request) {
        return List.of(new CodeGeneration.ContextItem("library", "Formatted output and forms", """
            Formatted and Forms are pre-imported in every Java notebook kernel.
            Show syntax-highlighted text with Formatted.show(format, content), Formatted.code(format, content),
            Formatted.json(content), Formatted.yaml(content), Formatted.xml(content), or
            Formatted.prettyJson(jsonText), Formatted.prettyYaml(yamlText), or
            Formatted.prettyXml(xmlText) to parse and indent JSON, YAML, or XML respectively.
            Pretty XML rejects DTDs and external entities. Pretty YAML may discard comments.
            Formats: json, yaml/yml, xml, html, css, javascript/js, java, sql, markdown/md, csv, text.
            Content is displayed as escaped text and never executed as HTML or JavaScript.
            Create an interactive form using:
            Forms.form("preferences").title("Preferences")
                .text("name", "Name", "Ada")
                .select("role", "Role", "reader", "admin")
                .checkbox("enabled", "Enabled", true)
                .number("threshold", "Threshold", 10)
                .textarea("notes", "Notes", "")
                .show();
            In a later cell use Forms.text("name"), Forms.bool("enabled"),
            Forms.number("threshold") (double), or Forms.value("notes") (Object).
            Field IDs are shared across forms within one notebook kernel and should be unique.
            Running a form cell again retains existing values. Input changes are synchronized before later cell execution.
            Forms and formatted output are saved in notebook outputs and displayed in offline HTML exports;
            exports cannot execute Java cells. Limits: 100 fields/form, 200 values/request, 100 KB values,
            1 million characters/formatted output, and 16 rich displays per snippet.
            """, Map.of("helpers", "Formatted,Forms")));
    }
}
