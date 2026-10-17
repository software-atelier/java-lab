package ch.software_atelier.java_lab.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CsvTest {
    @TempDir Path directory;

    @Test void roundTripsQuotedMultilineUnicodeEmptyFieldsBomAndDialectWithoutChangingValues() throws Exception {
        String original = "\ufeffid;name;notes\r\n001;\"Doe, Ada\";\"Line 1\nLine 2 with \"\"quotes\"\"\"\r\n002;Zürich;";
        Path input = directory.resolve("input.csv");Files.writeString(input, original);
        var table = Csv.load(input);
        assertThat(table.delimiter()).isEqualTo(';');
        assertThat(table.recordSeparator()).isEqualTo("\r\n");
        assertThat(table.bom()).isTrue();assertThat(table.trailingNewline()).isFalse();
        assertThat(table.records().get(0)).containsEntry("id", "001").containsEntry("name", "Doe, Ada")
            .containsEntry("notes", "Line 1\nLine 2 with \"quotes\"");
        assertThat(table.records().get(1)).containsEntry("notes", "");
        Path output = directory.resolve("output.csv");Csv.save(output, table);
        assertThat(Csv.load(output)).isEqualTo(table);
        assertThat(Files.readString(output)).startsWith("\ufeff").doesNotEndWith("\n");
        var rows = table.rows().stream().map(row -> new ArrayList<>(row)).toList();
        rows.get(2).set(1, "Grace; Hopper");
        Csv.save(output.toString(), table.withRows(rows));
        assertThat(Csv.load(output).records().get(1)).containsEntry("name", "Grace; Hopper");
        assertThat(table.records().get(1)).containsEntry("name", "Zürich");
        try (var files = Files.list(directory)) { assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp")); }
    }

    @Test void detectsCommonDialectsAndPreservesRaggedRowsAndBlankRecords() throws Exception {
        for (char delimiter : new char[]{',', ';', '\t', '|'}) {
            String text = "a" + delimiter + "b\n1" + delimiter + "2\n\n3\n";
            var table = Csv.parse(text, null);
            assertThat(table.delimiter()).isEqualTo(delimiter);
            assertThat(Csv.parse(new String(Csv.encode(table), java.nio.charset.StandardCharsets.UTF_8), delimiter).rows()).isEqualTo(table.rows());
            assertThatThrownBy(table::records).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Csv.parse("name;price\nA;1,25\nB;2,50\n", null).delimiter()).isEqualTo(';');
        assertThat(Csv.parse("a;b\nc;d", ',').rows().get(0)).containsExactly("a;b");
        var empty = Csv.parse("", null);assertThat(empty.rows()).isEmpty();assertThat(Csv.encode(empty)).isEmpty();
        assertThatThrownBy(() -> Csv.parse("a,a\n1,2\n", null).records()).hasMessageContaining("unique");
        assertThatThrownBy(() -> Csv.parse("a,b\n\"unterminated", null)).isInstanceOf(java.io.IOException.class);
    }

    @Test void rejectsInvalidEncodingAndOversizedTablesBeforeWriting() throws Exception {
        Path invalid = directory.resolve("invalid.csv");Files.write(invalid, new byte[]{(byte)0xc3, 0x28});
        assertThatThrownBy(() -> Csv.load(invalid)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> new Csv.Table(List.of(Collections.nCopies(101, "x")), ',', "\n", false, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Csv.Table(List.of(List.of("x".repeat(100001))), ',', "\n", false, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Csv.encode(new Csv.Table(List.of(List.of("\ud800")), ',', "\n", false, true))).isInstanceOf(java.io.IOException.class);
    }
}
