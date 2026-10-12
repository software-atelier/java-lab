package dev.mars.lab.display;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Kernel-local typed display transport for charts and tables. */
public final class Display {
    private static final List<byte[]> pending = new ArrayList<>();
    private static int pendingBytes;
    private static String transport = "";
    private static int transportOffset;
    private Display() {}

    static void validateSize(int width, int height) {
        if (width < 100 || height < 100 || width > 2400 || height > 2400 || (long) width * height > 4_000_000)
            throw new IllegalArgumentException("Chart dimensions must be 100–2400 pixels, with at most 4 million pixels");
    }

    static synchronized void emit(String mime, String title, int width, int height, byte[] content) {
        validateSize(width, height);
        if (pending.size() >= 16) throw new IllegalStateException("At most 16 displays can be shown by one Java snippet");
        if (pendingBytes + content.length > 16_000_000) throw new IllegalStateException("Display output exceeds the 16 MB snippet limit");
        try {
            var entry = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(entry)) {
                data.writeUTF(mime);data.writeUTF(title.substring(0, Math.min(title.length(), 500)));
                data.writeInt(width);data.writeInt(height);data.writeInt(content.length);data.write(content);
            }
            pending.add(entry.toByteArray());pendingBytes += content.length;
        } catch (IOException e) { throw new IllegalStateException("Could not collect chart output", e); }
    }

    /** JShell truncates long String values, so the host retrieves bounded chunks. */
    public static synchronized String drain() {
        if (pending.isEmpty()) return "";
        try {
            var bytes = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(bytes)) {
                data.writeInt(pending.size());
                for (byte[] entry : pending) data.write(entry);
            }
            transport = Base64.getEncoder().encodeToString(bytes.toByteArray());transportOffset = 0;
            return nextChunk();
        } catch (IOException e) { throw new IllegalStateException("Could not collect chart output", e); }
        finally { pending.clear();pendingBytes = 0; }
    }

    public static synchronized String nextChunk() {
        if (transportOffset >= transport.length()) { transport = "";transportOffset = 0;return ""; }
        int end = Math.min(transportOffset + 4096, transport.length());
        String chunk = transport.substring(transportOffset, end);transportOffset = end;return chunk;
    }
}
