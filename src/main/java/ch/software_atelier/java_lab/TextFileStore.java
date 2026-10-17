package ch.software_atelier.java_lab;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class TextFileStore {
    public static final int MAX_BYTES = 2000000;
    private final NotebookStore files;
    public TextFileStore(NotebookStore files) { this.files = files; }
    public record Document(String path, String content, String format, boolean bom, String recordSeparator, String revision, long size) {}
    public record Validation(boolean valid, String message, Integer line, Integer column) {}
    public Validation validateXml(String content) {
        if(content==null||content.length()>MAX_BYTES)throw invalid("Provide XML text up to 2 MB");
        try {ch.software_atelier.java_lab.data.XmlDocuments.parse(content);return new Validation(true,"Valid XML (well-formed)",null,null);}
        catch(org.xml.sax.SAXParseException e){return new Validation(false,e.getMessage().substring(0,Math.min(400,e.getMessage().length())),e.getLineNumber(),e.getColumnNumber());}
        catch(IOException|org.xml.sax.SAXException e){return new Validation(false,"XML could not be parsed; DTDs and external entities are not supported",null,null);}
    }

    public synchronized Document read(String path) throws IOException {
        byte[] bytes = bytes(resolve(path));
        String content;
        try { content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw invalid("This editor requires UTF-8 text. Use Download file for binary or other encodings"); }
        if (content.indexOf('\0') >= 0) throw invalid("Binary content cannot be edited as text. Use Download file");
        boolean bom = content.startsWith("\ufeff");if (bom) content = content.substring(1);
        var matcher = java.util.regex.Pattern.compile("\r\n|\r|\n").matcher(content);
        String separator = matcher.find() ? matcher.group() : "\n";
        return new Document(path, normalize(content), format(path), bom, separator, hash(bytes), bytes.length);
    }

    public synchronized Document save(String path, String content, boolean bom, String separator, String revision) throws IOException {
        Path target = resolve(path);
        if (content == null || content.length() > MAX_BYTES || content.indexOf('\0') >= 0 || revision == null || !revision.matches("[a-f0-9]{64}"))
            throw invalid("Provide UTF-8 text up to 2 MB and the file revision");
        if (separator == null || !java.util.List.of("\n", "\r\n", "\r").contains(separator)) throw invalid("Invalid line ending");
        String text = (bom ? "\ufeff" : "") + normalize(content).replace("\n", separator);
        byte[] contentBytes;
        try { var encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));contentBytes = new byte[encoded.remaining()];encoded.get(contentBytes); }
        catch (java.nio.charset.CharacterCodingException e) { throw invalid("Text contains invalid Unicode"); }
        if (contentBytes.length > MAX_BYTES) throw invalid("Text editor limit: 2 MB");
        Path temporary = Files.createTempFile(target.getParent(), ".java-lab-text-", ".tmp");
        try {
            Files.write(temporary, contentBytes);
            if (!hash(bytes(resolve(path))).equals(revision)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "File changed on disk. Your edits were kept; reload the current file before saving again");
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return read(path);
    }

    private Path resolve(String path) {
        if (path == null || !NotebookStore.fileType(path).equals("text")) throw invalid("This file type is available as a download, not in the text editor");
        return files.existingFile(path);
    }
    private byte[] bytes(Path path) throws IOException {
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Text editor limit: 2 MB; use Download file for larger files");
            return bytes;
        }
    }
    private String normalize(String value) { return value.replace("\r\n", "\n").replace('\r', '\n'); }
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String format(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (extension) { case "json", "xml", "java", "sql", "css" -> extension;case "js" -> "javascript";case "html", "htm" -> "html";case "yaml", "yml" -> "yaml";case "md" -> "markdown";default -> "text"; };
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
