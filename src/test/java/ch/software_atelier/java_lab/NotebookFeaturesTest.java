package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Timeout(120)
class NotebookFeaturesTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    @Test void valuesNeverEnterNotebookAndRenameDeleteManageOverrides() throws Exception {
        NotebookStore store = new NotebookStore(mapper, directory);
        String name = store.create("settings");
        var doc = store.read(name);
        var vars = doc.withObject("metadata").withObject("java_lab").putArray("variables");
        vars.addObject().put("name", "TOKEN").put("env", "JAVA_LAB_TEST_TOKEN").put("secret", true);
        vars.addObject().put("name", "HOST").put("defaultValue", "localhost");
        store.save(name, doc);
        store.updateValues(name, Map.of("TOKEN", "private-credential"), List.of());
        assertThat(store.effectiveValues(name)).containsEntry("TOKEN", "private-credential").containsEntry("HOST", "localhost");
        assertThat(store.valueSources(name)).contains(new NotebookValues.Source("TOKEN", "override"), new NotebookValues.Source("HOST", "default"));
        assertThat(Files.readString(directory.resolve(name))).doesNotContain("private-credential");
        var removed = store.read(name);
        ((com.fasterxml.jackson.databind.node.ArrayNode) removed.path("metadata").path("java_lab").path("variables")).remove(0);
        store.save(name, removed);
        var readded = store.read(name);
        ((com.fasterxml.jackson.databind.node.ArrayNode) readded.path("metadata").path("java_lab").path("variables")).addObject().put("name", "TOKEN").put("secret", true);
        store.save(name, readded);
        assertThat(store.effectiveValues(name)).doesNotContainKey("TOKEN");
        store.updateValues(name, Map.of("TOKEN", "private-credential"), List.of());
        try(var files=Files.list(directory)) { assertThat(files).allMatch(p -> p.getFileName().toString().endsWith(".jnb")); }
        String renamed = store.rename(name, "renamed");
        assertThat(store.effectiveValues(renamed)).containsEntry("TOKEN", "private-credential");
        store.updateValues(renamed, Map.of(), List.of("TOKEN"));
        assertThat(store.valueSources(renamed)).contains(new NotebookValues.Source("TOKEN", "unset"));
        store.delete(renamed);
    }
    @Test void rejectsCredentialValuesInDocumentAndInvalidCoordinates() throws Exception {
        NotebookStore store = new NotebookStore(mapper, directory);
        String name=store.create("invalid");
        var doc=store.read(name);
        doc.withObject("metadata").withObject("java_lab").putArray("variables").addObject().put("name","TOKEN").put("value","leak");
        assertThatThrownBy(()->store.save(name,doc)).hasMessageContaining("values cannot be saved");
        doc.withObject("metadata").withObject("java_lab").remove("variables");
        doc.withObject("metadata").withObject("java_lab").putArray("dependencies").add("../../bad");
        assertThatThrownBy(()->store.save(name,doc)).hasMessageContaining("Maven");
    }
    @Test void precedenceAndPerNotebookKernelIsolation() throws Exception {
        NotebookStore store = new NotebookStore(mapper, directory);
        String one=store.create("one"), two=store.create("two");
        for(String name:List.of(one,two)) {
            var doc=store.read(name);
            doc.withObject("metadata").withObject("java_lab").putArray("variables").addObject().put("name","ANSWER").put("defaultValue","default").put("env","TEST_ENV");
            store.save(name,doc);
        }
        var settings=store.settings(one);
        assertThat(NotebookValues.effective(settings,Map.of(),Map.of("TEST_ENV","environment"))).containsEntry("ANSWER","environment");
        assertThat(NotebookValues.effective(settings,Map.of("ANSWER","override"),Map.of("TEST_ENV","environment"))).containsEntry("ANSWER","override");
        store.updateValues(one,Map.of("ANSWER","only-one"),List.of());
        try(KernelService kernels=new KernelService(store)) {
            String a=kernels.create(one), b=kernels.create(two);
            assertThat(kernels.execute(a,"requireLabVar(\"ANSWER\")").outputs().toString()).contains("only-one");
            assertThat(kernels.execute(b,"requireLabVar(\"ANSWER\")").outputs().toString()).contains("default").doesNotContain("only-one");
        }
    }
    @Test void secretValuesAreRedactedFromKernelResponses() throws Exception {
        NotebookStore store = new NotebookStore(mapper, directory);
        String name = store.create("secret");
        var doc = store.read(name);
        doc.withObject("metadata").withObject("java_lab").putArray("variables").addObject().put("name", "TOKEN").put("secret", true);
        doc.withObject("metadata").withObject("java_lab").withArray("variables").addObject().put("name", "TOKEN_LONG").put("secret", true);
        store.save(name, doc);
        store.updateValues(name, Map.of("TOKEN", "very-private-token", "TOKEN_LONG", "long-secret-prefix-".repeat(80)), List.of());
        try (KernelService kernels = new KernelService(store)) {
            String id = kernels.create(name);
            assertThat(kernels.execute(id, "requireLabVar(\"TOKEN\")").outputs().toString()).contains("[REDACTED]").doesNotContain("very-private-token");
            assertThat(kernels.execute(id, "System.out.println(requireLabVar(\"TOKEN\"));").outputs().toString()).doesNotContain("very-private-token");
            assertThat(kernels.execute(id, "var credentials = Map.of(\"nested\", List.of(requireLabVar(\"TOKEN\")));").success()).isTrue();
            var nested = kernels.inspectSession(id, "inspect_variable", mapper.readTree("{\"variable\":\"credentials\",\"path\":[{\"entry\":0},{\"index\":0}]}"));
            assertThat(nested.toString()).contains("[REDACTED]").doesNotContain("very-private-token");
            assertThat(kernels.execute(id, "String longSecret = requireLabVar(\"TOKEN_LONG\");").success()).isTrue();
            assertThat(kernels.inspectSession(id, "inspect_variable", mapper.readTree("{\"variable\":\"longSecret\"}")).toString())
                .contains("[REDACTED]").doesNotContain("long-secret-prefix-");
        }
    }
    @Test void resolvesTransitiveJarOnlyForNotebook() throws Exception {
        NotebookStore store = new NotebookStore(mapper,directory);
        String with=store.create("with"), without=store.create("without");
        var doc=store.read(with);
        doc.withObject("metadata").withObject("java_lab").putArray("dependencies").add("org.apache.commons:commons-compress:1.27.1");
        store.save(with,doc);
        try(KernelService kernels=new KernelService(store)) {
            String a=kernels.create(with), b=kernels.create(without);
            assertThat(kernels.execute(a,"new org.apache.commons.compress.archivers.tar.TarArchiveEntry(\"x\").getName()").success()).isTrue();
            assertThat(kernels.execute(a,"new org.apache.commons.lang3.StringUtils() != null").success()).isTrue();
            assertThat(kernels.execute(b,"new org.apache.commons.compress.archivers.tar.TarArchiveEntry(\"x\")").success()).isFalse();
        }
    }
}
