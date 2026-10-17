package ch.software_atelier.java_lab.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodeGenerationServiceTest {
    private final CodeGenerationProvider provider = mock(CodeGenerationProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void combinesExtensibleContextAndExactSelectionWithoutLosingMetadata() throws Exception {
        var custom = (AiContextProvider) request -> List.of(new CodeGeneration.ContextItem("reference", "API contract", "Use sample()", Map.of("path", "api.txt")));
        var service = new CodeGenerationService(provider, List.of(new JavaRuntimeContext(), new ChartContext(), new EChartsContext(), new GridContext(), new DataFormatsContext(), custom), mapper);
        var target = new CodeGeneration.Target("int value = 1;", 12, 13);
        var request = new CodeGeneration.Request("Use 42", target, List.of(new CodeGeneration.ContextItem("notes", "User notes", "Keep the name value", Map.of("language", "java"))));
        when(provider.generate(anyList(), isNull(), any(CodeGeneration.Observer.class), same(target))).thenReturn(new CodeGeneration.Result("42", "test/model"));
        assertThat(service.generate(request).code()).isEqualTo("42");
        @SuppressWarnings("unchecked") var messages = (org.mockito.ArgumentCaptor<List<CodeGeneration.Message>>) (Object) org.mockito.ArgumentCaptor.forClass(List.class);
        verify(provider).generate(messages.capture(), isNull(), any(CodeGeneration.Observer.class), same(target));
        assertThat(messages.getValue()).hasSize(3);
        assertThat(messages.getValue().get(0).role()).isEqualTo("system");
        String context = messages.getValue().get(1).content();
        assertThat(context).contains("Keep the name value", "api.txt", "JShell", "java.util.*", "language");
        assertThat(context).contains("JFreeChart 1.5.6", "Charts.show", "ChartFactory.createBarChart", "headless");
        assertThat(context).contains("Apache ECharts 6.0.0", "ECharts.chart()", "PREFER THE JAVA WRAPPER", "Number... values");
        assertThat(context).contains("log(String msg, Object... args)", "Intermediate results", "last statement", "arrays");
        assertThat(context).contains("Grid.js 6.2.0", "Grid.table()", "pagination(int pageSize)", "application/vnd.gridjs+json");
        assertThat(context).contains("org.json:json 20260814", "Apache Commons CSV 1.14.1", "BOMInputStream", "never parse CSV with String.split");
        assertThat(context).contains("labVar(\\\"NAME\\\")", "requireLabVar(\\\"NAME\\\")", "Maven dependencies", "never includes resolved or local values");
        String instruction = messages.getValue().get(2).content();
        assertThat(instruction).contains("Use 42", "\"start\":12", "\"end\":13", "int value = 1;");
    }

    @Test void rejectsInvalidRangesAndExcessContextBeforeContactingProvider() {
        var service = new CodeGenerationService(provider, List.of(), mapper);
        for (var target : List.of(new CodeGeneration.Target("abc", -1, 2), new CodeGeneration.Target("abc", 2, 4), new CodeGeneration.Target("abc", 2, 2), new CodeGeneration.Target("abc", null, 2))) {
            assertThatThrownBy(() -> service.generate(new CodeGeneration.Request("Edit", target, null))).isInstanceOf(ResponseStatusException.class);
        }
        var largeContext = List.of(new CodeGeneration.ContextItem("notes", "Large", "x".repeat(200_001), Map.of()));
        assertThatThrownBy(() -> service.generate(new CodeGeneration.Request("Edit", new CodeGeneration.Target("", null, null), largeContext))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(provider);
    }

    @Test void applies200kLimitToContextIncludingProviderEntries() throws Exception {
        var target = new CodeGeneration.Target("", null, null);
        var provided = new CodeGeneration.ContextItem("runtime", "Provider note", "provider", Map.of("source", "built-in"));
        var base = new CodeGeneration.ContextItem("notes", "User note", "", Map.of());
        int overhead = mapper.writeValueAsString(base).length() + mapper.writeValueAsString(provided).length();
        var atLimit = new CodeGeneration.ContextItem("notes", "User note", "x".repeat(200_000 - overhead), Map.of());
        var service = new CodeGenerationService(provider, List.of(request -> List.of(provided)), mapper);
        when(provider.generate(anyList(), isNull(), any(CodeGeneration.Observer.class), same(target))).thenReturn(new CodeGeneration.Result("ok", "test/model"));
        assertThat(service.generate(new CodeGeneration.Request("Edit", target, List.of(atLimit))).code()).isEqualTo("ok");
        var overLimit = new CodeGeneration.ContextItem("notes", "User note", "x".repeat(200_001 - overhead), Map.of());
        assertThatThrownBy(() -> service.generate(new CodeGeneration.Request("Edit", target, List.of(overLimit))))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("200,000-character budget");
        verify(provider, times(1)).generate(anyList(), isNull(), any(CodeGeneration.Observer.class), same(target));
    }

    @Test void configurationExposesOnlyAvailabilityProviderAndModel() throws Exception {
        when(provider.name()).thenReturn("OpenAI-compatible API");
        when(provider.configured()).thenReturn(false);
        when(provider.model()).thenReturn("test/model");
        var service = new CodeGenerationService(provider, List.of(), mapper);
        assertThat(mapper.writeValueAsString(service.configuration())).isEqualTo("{\"configured\":false,\"provider\":\"OpenAI-compatible API\",\"model\":\"test/model\"}");
    }

    @Test void bindsToolsToTheRequestedKernelAndUsesTheNotebookReusePrompt() {
        var sessions = mock(SessionTools.class);
        var tools = mock(CodeGeneration.Tools.class);
        String kernel = "12345678-1234-1234-1234-123456789012";
        when(sessions.forKernel(kernel)).thenReturn(tools);
        when(provider.generate(anyList(), same(tools), any(CodeGeneration.Observer.class), any())).thenReturn(new CodeGeneration.Result("int answer = 42;", "test"));
        var service = new CodeGenerationService(provider, List.of(), mapper, sessions);
        assertThat(service.generate(new CodeGeneration.Request("Reuse data", new CodeGeneration.Target("", null, null), null, kernel)).code()).contains("42");
        @SuppressWarnings("unchecked") var messages = (org.mockito.ArgumentCaptor<List<CodeGeneration.Message>>) (Object) org.mockito.ArgumentCaptor.forClass(List.class);
        verify(provider).generate(messages.capture(), same(tools), any(CodeGeneration.Observer.class), any());
        assertThat(messages.getValue().get(0).content()).contains("top-level", "Do not repeat imports", "Do not duplicate existing",
            "without being displayed", "private fields", "session overview");
        verify(sessions).forKernel(kernel);
    }

    @Test void failedGenerationsFinalizeProgressWithoutPublishingSensitiveErrorDetails() {
        var service = new CodeGenerationService(provider, List.of(), mapper);
        String requestId = java.util.UUID.randomUUID().toString();
        when(provider.generate(anyList(), isNull(), any(CodeGeneration.Observer.class), any())).thenThrow(new IllegalStateException("private provider detail"));
        assertThatThrownBy(() -> service.generate(new CodeGeneration.Request("Edit", new CodeGeneration.Target("", null, null), null, null, requestId)))
            .isInstanceOf(IllegalStateException.class);
        var snapshot = service.progress(requestId);
        assertThat(snapshot.done()).isTrue();
        assertThat(snapshot.events()).extracting(GenerationProgress.Event::activity).contains("Code generation failed.");
        assertThat(snapshot.toString()).doesNotContain("private provider detail");
    }
}
