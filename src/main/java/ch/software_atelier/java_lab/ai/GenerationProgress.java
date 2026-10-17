package ch.software_atelier.java_lab.ai;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, ephemeral activity summaries. Never stores model reasoning, code, or tool results. */
@Component
public class GenerationProgress {
    public record Event(long elapsedMillis, String activity) {}
    public record Snapshot(boolean done, long elapsedMillis, List<Event> events, List<CodeGeneration.ResponseDiagnostics> diagnostics) {}
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public GenerationProgress() { this(Clock.systemUTC()); }
    GenerationProgress(Clock clock) { this.clock = clock; }

    public static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid generation request ID");
    }

    public synchronized void begin(String id) {
        validateId(id);
        long now = clock.millis();
        entries.entrySet().removeIf(e -> e.getValue().expired(now));
        if (entries.containsKey(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Generation request ID is already in use");
        if (entries.size() >= 128) {
            // Prefer discarding an old completed request over refusing new generation work.
            entries.entrySet().stream().filter(e -> e.getValue().snapshot(now).done())
                .min(Comparator.comparingLong(e -> e.getValue().started))
                .ifPresent(e -> entries.remove(e.getKey()));
        }
        if (entries.size() >= 128) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many active AI generations");
        var entry = new Entry(now);
        entry.add(now, "Preparing generation context…", false);
        entries.put(id, entry);
    }

    public void update(String id, String activity) {
        Entry entry = entries.get(id);
        if (entry != null) entry.add(clock.millis(), activity, false);
    }

    public void finish(String id, boolean success) {
        Entry entry = entries.get(id);
        if (entry != null) entry.add(clock.millis(), success ? "Code generation completed." : "Code generation failed.", true);
    }

    public void response(String id, CodeGeneration.ResponseDiagnostics diagnostics) {
        Entry entry = entries.get(id);
        if (entry != null) entry.response(clock.millis(), diagnostics);
    }

    public Snapshot snapshot(String id) {
        validateId(id);
        Entry entry = entries.get(id);
        long now = clock.millis();
        if (entry == null || entry.expired(now)) {
            if (entry != null) entries.remove(id, entry);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation progress is not available");
        }
        return entry.snapshot(now);
    }

    private static final class Entry {
        final long started;
        final ArrayDeque<Event> events = new ArrayDeque<>();
        final ArrayDeque<CodeGeneration.ResponseDiagnostics> diagnostics = new ArrayDeque<>();
        boolean done;
        long updated;
        Entry(long started) { this.started = this.updated = started; }
        synchronized void add(long now, String activity, boolean finished) {
            if (done) return;
            if (events.size() == 32) events.removeFirst();
            events.addLast(new Event(Math.max(0, now - started), activity));
            updated = now;
            done = finished;
        }
        synchronized boolean expired(long now) {
            return now - updated > (done ? Duration.ofMinutes(5) : Duration.ofHours(1)).toMillis();
        }
        synchronized void response(long now, CodeGeneration.ResponseDiagnostics response) {
            if (done) return;
            diagnostics.removeIf(previous -> previous.request() == response.request());
            if (diagnostics.size() == 66) diagnostics.removeFirst();
            diagnostics.addLast(response);
            updated = now;
        }
        synchronized Snapshot snapshot(long now) {
            return new Snapshot(done, Math.max(0, (done ? updated : now) - started), List.copyOf(events), List.copyOf(diagnostics));
        }
    }
}
