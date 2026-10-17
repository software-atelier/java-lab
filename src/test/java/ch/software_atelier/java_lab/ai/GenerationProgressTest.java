package ch.software_atelier.java_lab.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class GenerationProgressTest {
    private static final class MutableClock extends Clock {
        long millis;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
        public long millis() { return millis; }
    }

    @Test void snapshotsRemainIsolatedBoundedAndStopChangingAfterCompletion() {
        var clock = new MutableClock();
        var progress = new GenerationProgress(clock);
        String id = UUID.randomUUID().toString();
        progress.begin(id);
        clock.millis = 1000;
        progress.update(id, "Inspecting variables…");
        var first = progress.snapshot(id);
        assertThat(first.done()).isFalse();
        assertThat(first.elapsedMillis()).isEqualTo(1000);
        for (int i = 0; i < 40; i++) progress.update(id, "Activity " + i);
        assertThat(first.events()).hasSize(2);
        assertThat(progress.snapshot(id).events()).hasSize(32);
        progress.finish(id, true);
        progress.update(id, "late update");
        clock.millis = 2000;
        var finished = progress.snapshot(id);
        assertThat(finished.done()).isTrue();
        assertThat(finished.elapsedMillis()).isEqualTo(1000);
        assertThat(finished.events().get(31).activity()).isEqualTo("Code generation completed.");
    }

    @Test void expiresCompletedRequestsAndRejectsInvalidDuplicateAndUnknownIds() {
        var clock = new MutableClock();
        var progress = new GenerationProgress(clock);
        String id = UUID.randomUUID().toString();
        progress.begin(id);
        assertThatThrownBy(() -> progress.begin(id)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("already in use");
        assertThatThrownBy(() -> progress.snapshot("invalid")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Invalid");
        assertThatThrownBy(() -> progress.snapshot(UUID.randomUUID().toString())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("not available");
        progress.finish(id, false);
        clock.millis = Duration.ofMinutes(6).toMillis();
        assertThatThrownBy(() -> progress.snapshot(id)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("not available");
        progress.begin(id);
        assertThat(progress.snapshot(id).done()).isFalse();
    }

    @Test void boundsTheNumberOfRequestsAndReclaimsCompletedEntries() {
        var progress = new GenerationProgress();
        String first = UUID.randomUUID().toString();
        progress.begin(first);
        for (int i = 1; i < 128; i++) progress.begin(UUID.randomUUID().toString());
        assertThatThrownBy(() -> progress.begin(UUID.randomUUID().toString()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Too many active");
        progress.finish(first, true);
        String next = UUID.randomUUID().toString();
        progress.begin(next);
        assertThat(progress.snapshot(next).done()).isFalse();
    }

    @Test void responseMetadataIsBoundedIsolatedAndDoesNotChangeAfterCompletion() {
        var progress = new GenerationProgress();
        String id = UUID.randomUUID().toString();
        progress.begin(id);
        var before = progress.snapshot(id);
        for (int i = 1; i <= 70; i++) progress.response(id,
            new CodeGeneration.ResponseDiagnostics(i, "configured", "reported", 128000, "stop", 100L, 20L, null));
        progress.response(id, new CodeGeneration.ResponseDiagnostics(70, "configured", "resolved", 128000, "stop", 200L, 40L, null));
        assertThat(before.diagnostics()).isEmpty();
        assertThat(progress.snapshot(id).diagnostics()).hasSize(66);
        assertThat(progress.snapshot(id).diagnostics().get(0).request()).isEqualTo(5);
        assertThat(progress.snapshot(id).diagnostics().get(65).reportedModel()).isEqualTo("resolved");
        progress.finish(id, false);
        progress.response(id, new CodeGeneration.ResponseDiagnostics(71, "late", null, 128000, null, null, null, null));
        assertThat(progress.snapshot(id).diagnostics().get(65).request()).isEqualTo(70);
    }
}
