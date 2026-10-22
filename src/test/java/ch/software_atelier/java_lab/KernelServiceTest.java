package ch.software_atelier.java_lab;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@Timeout(60)
@Execution(ExecutionMode.CONCURRENT)
class KernelServiceTest {
    final KernelService kernels = new KernelService();
    @AfterEach void close() { kernels.close(); }

    @Test void formattedOutputAndFormValuesWorkAcrossCells() {
        String id = kernels.create();
        var formatted = kernels.execute(id, "Formatted.prettyJson(\"{\\\"name\\\":\\\"Ada\\\",\\\"active\\\":true}\");");
        assertThat(formatted.success()).as(formatted.outputs().toString()).isTrue();
        assertThat(formatted.outputs()).singleElement().satisfies(output -> {
            assertThat(output.get("output_type")).isEqualTo("display_data");
            var data = (Map<?, ?>) output.get("data");
            var value = (com.fasterxml.jackson.databind.JsonNode) data.get("application/vnd.java-lab.code+json");
            assertThat(value.path("format").asText()).isEqualTo("json");
            assertThat(value.path("content").asText()).contains("\n", "Ada");
        });

        var form = kernels.execute(id, "Forms.form(\"prefs\").title(\"Preferences\").text(\"name\", \"Name\", \"Ada\").select(\"role\", \"Role\", \"reader\", \"admin\").checkbox(\"enabled\", \"Enabled\", true).show();");
        assertThat(form.success()).as(form.outputs().toString()).isTrue();
        assertThat(form.outputs()).singleElement().satisfies(output -> {
            var data = (Map<?, ?>) output.get("data");
            var value = (com.fasterxml.jackson.databind.JsonNode) data.get("application/vnd.java-lab.form+json");
            assertThat(value.path("id").asText()).isEqualTo("prefs");
            assertThat(value.path("fields").size()).isEqualTo(3);
            assertThat(value.path("fields").get(0).path("value").asText()).isEqualTo("Ada");
        });

        var later = kernels.execute(id, "log(\"% / % / %\", Forms.text(\"name\"), Forms.text(\"role\"), Forms.bool(\"enabled\"));", Map.of("name", "Grace", "role", "admin", "enabled", false));
        assertThat(later.success()).as(later.outputs().toString()).isTrue();
        assertThat(later.outputs()).singleElement().satisfies(o -> assertThat(o.get("text")).isEqualTo("Grace / admin / false\n"));
        var rerender = kernels.execute(id, "Forms.form(\"prefs\").text(\"name\", \"Name\", \"Ada\").show();", Map.of("name", "Grace", "role", "admin", "enabled", false));
        var rerenderData = (Map<?, ?>) rerender.outputs().get(0).get("data");
        var rerenderValue = (com.fasterxml.jackson.databind.JsonNode) rerenderData.get("application/vnd.java-lab.form+json");
        assertThat(rerenderValue.path("fields").get(0).path("value").asText()).isEqualTo("Grace");
        String independent = kernels.create();
        assertThat(kernels.execute(independent, "log(\"%\", Forms.text(\"name\"));").outputs()).singleElement().satisfies(o -> assertThat(o.get("text")).isEqualTo("null\n"));
        assertThat(kernels.execute(id, "Formatted.prettyJson(\"not json\");").success()).isFalse();
        assertThat(kernels.execute(id, "Formatted.prettyJson(\"{\\\"ok\\\":true} trailing\");").success()).isFalse();
        assertThatThrownBy(() -> kernels.execute(id, "1 + 1", Map.of("bad id", "x"))).isInstanceOf(ResponseStatusException.class);
    }

    @Test void prettyYamlAndXmlFormatSafelyAndReportInvalidInput() {
        String id = kernels.create();
        var yaml = kernels.execute(id, "Formatted.prettyYaml(\"person: {name: Ada, active: true}\");");
        assertThat(yaml.success()).as(yaml.outputs().toString()).isTrue();
        var yamlData = (Map<?, ?>) yaml.outputs().get(0).get("data");
        var yamlValue = (com.fasterxml.jackson.databind.JsonNode) yamlData.get("application/vnd.java-lab.code+json");
        assertThat(yamlValue.path("format").asText()).isEqualTo("yaml");
        assertThat(yamlValue.path("content").asText()).contains("person:", "  name: Ada", "  active: true");

        var xml = kernels.execute(id, "Formatted.prettyXml(\"<person><name>Ada</name><active>true</active></person>\");");
        assertThat(xml.success()).as(xml.outputs().toString()).isTrue();
        var xmlData = (Map<?, ?>) xml.outputs().get(0).get("data");
        var xmlValue = (com.fasterxml.jackson.databind.JsonNode) xmlData.get("application/vnd.java-lab.code+json");
        assertThat(xmlValue.path("format").asText()).isEqualTo("xml");
        assertThat(xmlValue.path("content").asText()).contains("\n  <name>Ada</name>", "\n  <active>true</active>");

        assertThat(kernels.execute(id, "Formatted.prettyYaml(\"a: [broken\");").success()).isFalse();
        var unsafe = kernels.execute(id, "Formatted.prettyXml(\"<!DOCTYPE person [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><person>&xxe;</person>\");");
        assertThat(unsafe.success()).isFalse();
        assertThat(unsafe.outputs().toString()).doesNotContain("root:");
    }

    @Test void statePersistsAcrossCellsAndKernelsAreIndependent() {
        String first = kernels.create();
        String second = kernels.create();
        var definitions = kernels.execute(first, "int answer = 40;\nint add(int x) { return x + 2; }");
        assertThat(definitions.success()).isTrue();
        var value = kernels.execute(first, "log(\"%\", String.valueOf(add(answer)));");
        assertThat(value.executionCount()).isEqualTo(2);
        assertThat(value.success()).isTrue();
        assertThat(value.outputs()).singleElement().satisfies(output -> assertThat(output.get("text")).isEqualTo("42\n"));
        assertThat(kernels.execute(second, "answer").success()).isFalse();
        kernels.remove(first);
        assertThatThrownBy(() -> kernels.execute(first, "1 + 1")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void capturesStreamsAndReportsCompilationRuntimeAndIncompleteErrors() {
        String id = kernels.create();
        var output = kernels.execute(id, "System.out.println(\"hello\");\nSystem.err.println(\"oops\");");
        assertThat(output.success()).isTrue();
        assertThat(output.outputs()).hasSize(2);
        assertThat(output.outputs()).anySatisfy(o -> { assertThat(o.get("name")).isEqualTo("stdout"); assertThat(o.get("text")).isEqualTo("hello\n"); });
        assertThat(output.outputs()).anySatisfy(o -> { assertThat(o.get("name")).isEqualTo("stderr"); assertThat(o.get("text")).isEqualTo("oops\n"); });
        var compile = kernels.execute(id, "int bad = \"text\";");
        assertThat(compile.success()).isFalse();
        assertThat(compile.outputs()).anySatisfy(o -> assertThat(o.get("ename")).isEqualTo("CompilationError"));
        var runtime = kernels.execute(id, "throw new IllegalArgumentException(\"test failure\");");
        assertThat(runtime.success()).isFalse();
        assertThat(runtime.outputs()).anySatisfy(o -> assertThat(o.get("evalue")).isEqualTo("test failure"));
        assertThat(kernels.execute(id, "if (true) {").success()).isFalse();
        assertThat(kernels.execute(id, "List.of(1, 2, 3).size()").success()).isTrue();
    }

    @Test void canInterruptRunningCodeAndReuseTheSession() throws Exception {
        String id = kernels.create();
        var execution = CompletableFuture.supplyAsync(() -> kernels.execute(id, "while (true) {}"));
        // Stop is safe to request repeatedly while the remote snippet starts up.
        for (int i = 0; i < 40 && !execution.isDone(); i++) {
            Thread.sleep(150);
            kernels.interrupt(id);
        }
        execution.get(5, TimeUnit.SECONDS);
        assertThat(kernels.execute(id, "log(\"%\", String.valueOf(6 * 7));").outputs()).singleElement().satisfies(o -> assertThat(o.get("text")).isEqualTo("42\n"));
    }

    @Test void anImmediateInterruptDuringCompilationAlsoStopsTheUpcomingRemoteLoop() throws Exception {
        String id=kernels.create();
        var execution=CompletableFuture.supplyAsync(()->kernels.execute(id,"while (true) {}"));
        kernels.interrupt(id);
        execution.get(5,TimeUnit.SECONDS);
        var next=kernels.execute(id,"log(\"still usable\");");
        assertThat(next.success()).isTrue();
        assertThat(next.outputs()).singleElement().satisfies(o->assertThat(o.get("text")).isEqualTo("still usable\n"));
    }

    @Test void consoleOutputIsDisplayedAndOnlyTheLastOrdinaryValueIsEchoed() {
        String id = kernels.create();
        var screenshot = kernels.execute(id, "// Welcome to Java Lab\nSystem.out.println(\"Hello, Java!\");\nSystem.out.printf(\"test %d\", 3*4)");
        assertThat(screenshot.success()).isTrue();
        assertThat(screenshot.outputs()).hasSize(2).allSatisfy(o -> assertThat(o.get("output_type")).isEqualTo("stream"));
        assertThat(screenshot.outputs()).extracting(o -> o.get("text")).containsExactly("Hello, Java!\n", "test 12");
        for (String expression : new String[]{"System.out.printf(\"%d\", 12);", "System.out.format(\"%d\", 12)", "System.err.append(\"12\")"}) {
            var result = kernels.execute(id, expression);
            assertThat(result.success()).isTrue();
            assertThat(result.outputs()).singleElement().satisfies(o -> {
                assertThat(o.get("output_type")).isEqualTo("stream");
                assertThat(o.get("text")).isEqualTo("12");
            });
        }
        var value = kernels.execute(id, "3 * 4");
        assertThat(value.outputs()).singleElement().satisfies(o->assertThat(o.get("data")).isEqualTo(Map.of("text/plain","12")));
        var object = kernels.execute(id, "new Object()");
        assertThat(object.outputs()).singleElement().satisfies(o->assertThat(o.get("output_type")).isEqualTo("execute_result"));
        var declarations = kernels.execute(id, "int value = 42;\nString message = \"hello\";\nList<Integer> list = List.of(1,2,3);\nmessage");
        assertThat(declarations.success()).isTrue();
        assertThat(declarations.outputs()).singleElement().satisfies(o->assertThat(o.get("data")).isEqualTo(Map.of("text/plain","\"hello\"")));
    }

    @Test void completionUsesSessionTypesAndReturnsTheCorrectReplacementAnchorWithoutExecutingCode() {
        String id = kernels.create();
        kernels.execute(id, "String greeting = \"hello\";\nint notebookCounter = 7;");
        var members = kernels.complete(id, "greeting.toU", 12);
        assertThat(members.anchor()).isEqualTo("greeting.".length());
        assertThat(members.suggestions()).extracting(KernelService.Suggestion::text).anyMatch(s -> s.startsWith("toUpperCase("));
        var variables = kernels.complete(id, "notebookC", 9);
        assertThat(variables.anchor()).isZero();
        assertThat(variables.suggestions()).extracting(KernelService.Suggestion::text).contains("notebookCounter");
        String multi = "System.out.println(\"not executed\");\nnotebookC";
        var multiple = kernels.complete(id, multi, multi.length());
        assertThat(multiple.anchor()).isEqualTo(multi.lastIndexOf("notebookC"));
        assertThat(multiple.suggestions()).extracting(KernelService.Suggestion::text).contains("notebookCounter");
        var check = kernels.execute(id, "log(\"%\", String.valueOf(notebookCounter));");
        assertThat(check.executionCount()).isEqualTo(2);
        assertThat(check.outputs()).singleElement().satisfies(o -> assertThat(o.get("text")).isEqualTo("7\n"));
        assertThatThrownBy(() -> kernels.complete(id, "abc", 4)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void rendersBundledChartsAsPngWithRequestedDimensionsAndSupportsChartExpressions() throws Exception {
        String id = kernels.create();
        var result = kernels.execute(id, """
            var sales = new DefaultCategoryDataset();
            sales.addValue(12, "Sales", "Jan");
            sales.addValue(19, "Sales", "Feb");
            var bar = ChartFactory.createBarChart("Monthly sales", "Month", "Sales", sales);
            Charts.show(bar, 640, 360);
            Charts.show(bar, 800, 450);
            """);
        assertThat(result.success()).as(result.outputs().toString()).isTrue();
        var charts = result.outputs().stream().filter(o -> "display_data".equals(o.get("output_type"))).toList();
        assertThat(charts).hasSize(2);
        for (int i = 0; i < charts.size(); i++) {
            var data = (Map<?, ?>) charts.get(i).get("data");
            byte[] bytes = java.util.Base64.getDecoder().decode((String) data.get("image/png"));
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            assertThat(image).isNotNull();
            assertThat(image.getWidth()).isEqualTo(i == 0 ? 640 : 800);
            assertThat(image.getHeight()).isEqualTo(i == 0 ? 360 : 450);
            assertThat(data.get("text/plain")).isEqualTo("Monthly sales");
        }
        var expression = kernels.execute(id, "bar");
        assertThat(expression.success()).isTrue();
        assertThat(expression.outputs()).singleElement().satisfies(o -> assertThat(o.get("output_type")).isEqualTo("display_data"));
        assertThat(kernels.execute(id, "42").outputs()).singleElement().satisfies(o->assertThat(o.get("data")).isEqualTo(Map.of("text/plain","42")));
        var second = kernels.create();
        assertThat(kernels.execute(second, "System.out.print(\"fresh\");").outputs()).singleElement().satisfies(o -> assertThat(o.get("text")).isEqualTo("fresh"));
        var completions = kernels.complete(id, "Charts.sh", 9);
        assertThat(completions.suggestions()).extracting(KernelService.Suggestion::text).contains("show(");
    }

    @Test void chartValidationReportsErrorsAndDoesNotLeakPendingImagesIntoNextCell() {
        String id = kernels.create();
        var error = kernels.execute(id, """
            Charts.show(ChartFactory.createPieChart("Pie", new DefaultPieDataset<String>(), true, false, false), 99, 100);
            """);
        assertThat(error.success()).isFalse();
        assertThat(error.outputs()).anySatisfy(o -> assertThat(o.get("ename")).isEqualTo("java.lang.IllegalArgumentException"));
        var good = kernels.execute(id, """
            var shares = new DefaultPieDataset<String>();
            shares.setValue("Java", 65); shares.setValue("Other", 35);
            Charts.show(ChartFactory.createPieChart("Language share", shares, true, true, false));
            """);
        assertThat(good.success()).as(good.outputs().toString()).isTrue();
        assertThat(good.outputs()).anySatisfy(o -> assertThat(o.get("output_type")).isEqualTo("display_data"));
        assertThat(kernels.execute(id, "System.out.print(\"done\");").outputs()).singleElement().satisfies(o -> assertThat(o.get("text")).isEqualTo("done"));
    }

    @Test void echartsDisplayPreservesJsonAndDimensionsAndSharesOrderedTransportWithPng() {
        String id = kernels.create();
        String option = "{\"title\":{\"text\":\"Revenue\"},\"xAxis\":{\"type\":\"category\",\"data\":[\"Jan\",\"Feb\"]},\"yAxis\":{},\"series\":[{\"type\":\"bar\",\"data\":[12,19]}]}";
        var result = kernels.execute(id, "{ Charts.show(ChartFactory.createPieChart(\"PNG\", new DefaultPieDataset<String>(), false, false, false), 320, 200); ECharts.show(\""+option.replace("\"", "\\\"")+"\", 800, 450); }");
        assertThat(result.success()).as(result.outputs().toString()).isTrue();
        assertThat(result.outputs()).hasSize(2);
        var image = (Map<?, ?>) result.outputs().get(0).get("data");
        assertThat(image.containsKey("image/png")).isTrue();
        var data = (Map<?, ?>) result.outputs().get(1).get("data");
        assertThat(data.get("text/plain")).isEqualTo("Revenue");
        var json = (com.fasterxml.jackson.databind.JsonNode) data.get("application/vnd.apache.echarts+json");
        assertThat(json.path("series").get(0).path("data").get(1).asInt()).isEqualTo(19);
        var metadata = (Map<?, ?>) result.outputs().get(1).get("metadata");
        assertThat(metadata.get("application/vnd.apache.echarts+json")).isEqualTo(Map.of("width",800,"height",450));
        assertThat(kernels.complete(id,"ECharts.sh",10).suggestions()).extracting(KernelService.Suggestion::text).contains("show(");
        assertThat(kernels.execute(id,"42").outputs()).singleElement().satisfies(o->assertThat(o.get("data")).isEqualTo(Map.of("text/plain","42")));
    }

    @Test void echartsRejectsJavascriptAndInvalidJsonAndTheAiExampleRuns() {
        String id = kernels.create();
        var invalid = kernels.execute(id, "ECharts.show(\"{series: []}\");");
        assertThat(invalid.success()).isFalse();
        assertThat(invalid.outputs()).anySatisfy(o -> assertThat(o.get("ename")).isEqualTo("ChartError"));
        var context = new ch.software_atelier.java_lab.ai.EChartsContext().contextFor(null).get(0).content();
        String example = context.substring(context.indexOf("ECharts.chart()\n"));
        var result = kernels.execute(id, example);
        assertThat(result.success()).as(result.outputs().toString()).isTrue();
        assertThat(result.outputs()).singleElement().satisfies(o -> assertThat(o.get("output_type")).isEqualTo("display_data"));
    }

    @Test void javaWrapperIsAvailableInRemoteKernelAndJFreeChartStillDisplaysAfterECharts() throws Exception {
        String id=kernels.create();
        var interactive=kernels.execute(id,"""
            var interactive = ECharts.chart().title("Java wrapper").categories("Jan", "Feb").bar("Sales",12,19).size(800,400);
            interactive.show();
            """);
        assertThat(interactive.success()).as(interactive.outputs().toString()).isTrue();
        assertThat(interactive.outputs()).singleElement().satisfies(o -> assertThat(((Map<?,?>)o.get("data")).containsKey("application/vnd.apache.echarts+json")).isTrue());
        var expression=kernels.execute(id,"interactive");
        assertThat(expression.success()).isTrue();
        assertThat(expression.outputs()).singleElement().satisfies(o -> assertThat(o.get("output_type")).isEqualTo("display_data"));
        var png=kernels.execute(id,"""
            var sales = new DefaultCategoryDataset();
            sales.addValue(12,"Sales","Jan");sales.addValue(19,"Sales","Feb");sales.addValue(15,"Sales","Mar");
            var salesChart = ChartFactory.createBarChart("Monthly sales · JFreeChart","Month","Sales",sales);
            Charts.show(salesChart,900,450);
            """);
        assertThat(png.success()).as(png.outputs().toString()).isTrue();
        var output=png.outputs().stream().filter(o->"display_data".equals(o.get("output_type"))).findFirst().orElseThrow();
        byte[] bytes=java.util.Base64.getDecoder().decode((String)((Map<?,?>)output.get("data")).get("image/png"));
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertThat(image.getWidth()).isEqualTo(900);assertThat(image.getHeight()).isEqualTo(450);
        assertThat(kernels.complete(id,"ECharts.chart().cat",19).suggestions()).extracting(KernelService.Suggestion::text).contains("categories(");
    }

    @Test void logReplacesPercentPlaceholdersLiterallyAndRemainsAvailableAcrossCells() {
        String id = kernels.create();
        var definition = kernels.execute(id, "String user = \"Ada\"; int total = 42;");
        assertThat(definition.outputs()).singleElement().satisfies(o->assertThat(o.get("data")).isEqualTo(Map.of("text/plain","42")));
        var result = kernels.execute(id, "log(\"Hello %, total = %\", user, total);");
        assertThat(result.success()).isTrue();
        assertThat(result.outputs()).singleElement().satisfies(o -> {
            assertThat(o.get("output_type")).isEqualTo("stream");assertThat(o.get("name")).isEqualTo("stdout");
            assertThat(o.get("text")).isEqualTo("Hello Ada, total = 42\n");
        });
        var literals = kernels.execute(id, "log(\"% / % / %\", \"$1\\\\path%\", \"second\");");
        assertThat(literals.outputs()).singleElement().satisfies(o -> assertThat(o.get("text")).isEqualTo("$1\\path% / second / %\n"));
        var extra = kernels.execute(id, "log(\"no placeholders\", \"ignored\");");
        assertThat(extra.outputs()).singleElement().satisfies(o -> assertThat(o.get("text")).isEqualTo("no placeholders\n"));
        var nulls = kernels.execute(id, "log(\"%\", (String) null); log(null); log(\"unchanged %\");");
        assertThat(nulls.outputs()).extracting(o -> o.get("text")).containsExactly("null\n", "null\n", "unchanged %\n");
        var objects=kernels.execute(id,"log(\"% / % / % / %\", 42, true, new int[]{1,2}, new StringBuilder(\"value\"));");
        assertThat(objects.outputs()).singleElement().satisfies(o->assertThat(o.get("text")).isEqualTo("42 / true / [1, 2] / value\n"));
        assertThat(kernels.complete(id,"lo",2).suggestions()).extracting(KernelService.Suggestion::text).contains("log(");
    }

    @Test void gridWrapperDisplaysTypedTablesAndSupportsCompletionAndAutomaticTableExpressions() {
        String id=kernels.create();
        var result=kernels.execute(id,"""
            var table = Grid.table().title("Sales").columns("Month","Amount").row("Jan",12).row("Feb",19).pagination(1);
            table.show();
            """);
        assertThat(result.success()).as(result.outputs().toString()).isTrue();
        assertThat(result.outputs()).singleElement().satisfies(o->{
            assertThat(o.get("output_type")).isEqualTo("display_data");
            var data=(Map<?,?>)o.get("data");
            assertThat(data.get("text/plain")).isEqualTo("Sales");
            var option=(com.fasterxml.jackson.databind.JsonNode)data.get("application/vnd.gridjs+json");
            assertThat(option.path("data").get(1).get(1).asInt()).isEqualTo(19);
        });
        assertThat(kernels.execute(id,"table").outputs()).singleElement().satisfies(o->assertThat(o.get("output_type")).isEqualTo("display_data"));
        String completion="Grid.table().col";
        assertThat(kernels.complete(id,completion,completion.length()).suggestions()).extracting(KernelService.Suggestion::text).contains("columns(");
        var bad=kernels.execute(id,"Grid.table().columns(\"A\",\"B\").row(1).show();");
        assertThat(bad.success()).isFalse();
        assertThat(kernels.execute(id,"42").outputs()).singleElement().satisfies(o->assertThat(o.get("data")).isEqualTo(Map.of("text/plain","42")));
        var text=new ch.software_atelier.java_lab.ai.GridContext().contextFor(null).get(0).content();
        var example=kernels.execute(id,text.substring(text.indexOf("Grid.table()\n")));
        assertThat(example.success()).as(example.outputs().toString()).isTrue();
        assertThat(example.outputs()).singleElement().satisfies(o->assertThat(o.get("output_type")).isEqualTo("display_data"));
    }

    @Test void lastResultIgnoresIntermediateValuesAndTrailingCommentsButKeepsExplicitLogOutput() {
        String id=kernels.create();
        var result=kernels.execute(id,"int x = 40;\nnew Object();\nlog(\"x = %\", x);\nx + 2\n// trailing comment\n/* another comment */");
        assertThat(result.success()).as(result.outputs().toString()).isTrue();
        assertThat(result.outputs()).hasSize(2);
        assertThat(result.outputs().get(0).get("text")).isEqualTo("x = 40\n");
        assertThat(result.outputs().get(1).get("data")).isEqualTo(Map.of("text/plain","42"));
        var voidLast=kernels.execute(id,"x + 1;\nlog(\"done\");");
        assertThat(voidLast.outputs()).singleElement().satisfies(o->assertThat(o.get("text")).isEqualTo("done\n"));
    }

    @Test void inspectionReportsJavaSignaturesWithoutExecutingEarlierSnippets() {
        String id=kernels.create();
        String code="System.out.println(\"not executed\");\nlog(";
        var inspected=kernels.inspect(id,code,code.length());
        assertThat(inspected.signatures()).anySatisfy(signature->assertThat(signature).contains("log(","Object"));
        var output=kernels.execute(id,"log(\"visible\");");
        assertThat(output.executionCount()).isEqualTo(1);
        assertThat(output.outputs()).singleElement().satisfies(o->assertThat(o.get("text")).isEqualTo("visible\n"));
        assertThatThrownBy(()->kernels.inspect(id,"x",2)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void bundledOrgJsonParsesAndBuildsJsonInTheRemoteKernel() {
        String id=kernels.create();
        var result=kernels.execute(id,"""
            var json = new JSONObject().put("name", "Ada").put("sales", 42).put("items", new JSONArray(List.of(1,2,3)));
            var copy = new JSONObject(json.toString());
            log("Name: %, sales: %, item: %", copy.getString("name"), copy.getInt("sales"), copy.getJSONArray("items").getInt(1));
            """);
        assertThat(result.success()).as(result.outputs().toString()).isTrue();
        assertThat(result.outputs()).singleElement().satisfies(o->assertThat(o.get("text")).isEqualTo("Name: Ada, sales: 42, item: 2\n"));
        String code="new JSONObject().get";
        assertThat(kernels.complete(id,code,code.length()).suggestions()).extracting(KernelService.Suggestion::text).contains("getString(");
    }

    @Test void commonsCsvHandlesQuotedCommasEscapedQuotesMultilineAndEmptyFields() throws Exception {
        String id=kernels.create();
        String csv="name,sales,notes\r\n\"Doe, Ada\",12,\"said \"\"hi\"\"\"\r\nGrace,19,\"first line\nsecond line\"\r\nEmpty,0,\r\n";
        String literal=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(csv);
        var result=kernels.execute(id,"""
            var format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();
            """+"try(var parser = CSVParser.parse("+literal+", format)) { var rows = parser.getRecords(); log(\"count=% first=% quote=% multiline=% empty=%\", rows.size(), rows.get(0).get(\"name\"), rows.get(0).get(\"notes\"), rows.get(1).get(\"notes\"), rows.get(2).get(\"notes\").isEmpty()); }");
        assertThat(result.success()).as(result.outputs().toString()).isTrue();
        assertThat(result.outputs()).singleElement().satisfies(o->assertThat(o.get("text")).isEqualTo("count=3 first=Doe, Ada quote=said \"hi\" multiline=first line\nsecond line empty=true\n"));
        var bad=kernels.execute(id,"try(var parser = CSVParser.parse(\"\\\"unclosed\", CSVFormat.RFC4180)) { parser.getRecords(); }");
        assertThat(bad.success()).isFalse();
        assertThat(bad.outputs()).anySatisfy(o->assertThat(o.get("output_type")).isEqualTo("error"));
    }

    @Test void csvDependenciesSupportBomInputAlternateDelimitersPrintingAndTheAiExample() {
        String id=kernels.create();
        byte[] text="name,value\nAda,42\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bom=new byte[text.length+3];bom[0]=(byte)0xef;bom[1]=(byte)0xbb;bom[2]=(byte)0xbf;System.arraycopy(text,0,bom,3,text.length);
        String encoded=java.util.Base64.getEncoder().encodeToString(bom);
        var result=kernels.execute(id,"""
            var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
            """+"try(var input = BOMInputStream.builder().setInputStream(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(\""+encoded+"\"))).get(); var reader = new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8); var parser = CSVParser.parse(reader,format)) { log(\"%=%\",parser.getHeaderNames().get(0),parser.getRecords().get(0).get(\"value\")); }");
        assertThat(result.success()).as(result.outputs().toString()).isTrue();
        assertThat(result.outputs()).singleElement().satisfies(o->assertThat(o.get("text")).isEqualTo("name=42\n"));
        var tsv=kernels.execute(id,"try(var parser = CSVParser.parse(\"name\\tsales\\nAda\\t42\\n\", CSVFormat.TDF.builder().setHeader().setSkipHeaderRecord(true).get())) { log(\"%\",parser.getRecords().get(0).get(\"sales\")); }");
        assertThat(tsv.success()).isTrue();assertThat(tsv.outputs()).singleElement().satisfies(o->assertThat(o.get("text")).isEqualTo("42\n"));
        var printer=kernels.execute(id,"var output = new StringBuilder(); try(var printer = new CSVPrinter(output, CSVFormat.RFC4180)) { printer.printRecord(\"A, B\",\"Q\"); } log(\"%\", output.toString());");
        assertThat(printer.success()).as(printer.outputs().toString()).isTrue();
        assertThat(printer.outputs()).singleElement().satisfies(o->assertThat(o.get("text")).isEqualTo("\"A, B\",Q\r\n\n"));
        assertThat(kernels.execute(id,"log(\"%\",org.apache.commons.codec.binary.Base64.encodeBase64String(new byte[]{1,2}));").success()).isTrue();
        var context=new ch.software_atelier.java_lab.ai.DataFormatsContext().contextFor(null).get(0).content();
        var example=kernels.execute(id,context.substring(context.indexOf("var format = CSVFormat.RFC4180.builder().setHeader()")));
        assertThat(example.success()).as(example.outputs().toString()).isTrue();
        assertThat(example.outputs()).singleElement().satisfies(o->assertThat(o.get("output_type")).isEqualTo("display_data"));
    }
}
