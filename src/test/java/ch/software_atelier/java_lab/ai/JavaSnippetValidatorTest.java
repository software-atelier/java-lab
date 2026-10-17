package ch.software_atelier.java_lab.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class JavaSnippetValidatorTest {
    @Test void acceptsMixedNotebookSnippetsWithoutResolvingTypesOrExecutingCode() {
        String property = "java.lab.ai.validation.sideEffect";
        System.clearProperty(property);
        String source = """
            import java.sql.*;
            class Customer { private String name; }
            int next(int n) { return n + 1; }
            String query = ""\"\nSELECT 'a;b' FROM missing_table\n""\";
            var rows = unknownSessionVariable.fetch();
            try (var statement = connection.createStatement()) {
                while (resultSet.next()) { rows.add(resultSet.getString(1)); }
            }
            System.setProperty("java.lab.ai.validation.sideEffect", "executed");
            rows
            // trailing comment
            """;
        assertThat(JavaSnippetValidator.problem(null, source)).isNull();
        assertThat(System.getProperty(property)).isNull();
        assertThat(JavaSnippetValidator.problem(null, "// intentionally commented out")).isNull();
    }

    @Test void rejectsUnfinishedBlocksStringsCommentsAndMalformedJava() {
        for (String source : new String[]{"try (var rs = st.executeQuery(sql)) { while (rs unnecessary.",
            "while (rs.next()) {", "String name = \"unfinished", "/* unfinished comment", "int value = ;", "var result = new", "if (ready) { run();"})
            assertThat(JavaSnippetValidator.problem(null, source)).as(source).isNotNull();
    }

    @Test void validatesTheAssembledCellRatherThanTreatingASelectionAsAWholeSnippet() {
        String original = "int total = 1 + 2;";
        var expression = new CodeGeneration.Target(original, 12, 17);
        assertThat(JavaSnippetValidator.problem(expression, "3 + 4")).isNull();
        String block = "if (ready) { old(); }";
        var range = new CodeGeneration.Target(block, block.indexOf("old"), block.indexOf("old") + "old();".length());
        assertThat(JavaSnippetValidator.problem(range, "replacement();")).isNull();
        assertThat(JavaSnippetValidator.problem(range, "while (true) {")).isNotNull();
        assertThat(JavaSnippetValidator.problem(new CodeGeneration.Target("a".repeat(100000), 0, 1), "longer")).contains("100,000");
    }
}
