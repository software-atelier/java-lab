package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Portable connection metadata; credentials are referenced, never embedded. */
public record SqlConfiguration(String maven, String url, String credentialId) {
    static final String PREFIX = "-- java-lab-sql: ";
    private static final ObjectMapper JSON = new ObjectMapper();
    static SqlConfiguration parse(String text) {
        if (!text.startsWith(PREFIX)) return null;
        try { return JSON.readValue(text.substring(PREFIX.length()).split("\n", 2)[0], SqlConfiguration.class); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid java-lab-sql metadata comment"); }
    }
    static String source(String text) { return text.startsWith(PREFIX) ? (text.contains("\n") ? text.substring(text.indexOf('\n') + 1) : "") : text; }
    String header() {
        try { return PREFIX + JSON.writeValueAsString(this) + "\n"; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    void validate() {
        if (maven == null || !maven.matches("[^\\s:]+:[^\\s:]+:[^\\s:]+") || url == null || !url.startsWith("jdbc:") || url.contains("\n")
                || credentialId == null || !credentialId.matches("[a-f0-9-]{36}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide JDBC Maven coordinates (groupId:artifactId:version), a JDBC URL and a credential ID");
        if (url.matches("(?is).*(?:password|passwd|pwd|user|username)\\s*=.*") || url.matches("(?s).*//[^/]*@.*")
                || url.matches("(?is)jdbc:oracle:(?:thin|oci):[^@]+@.*"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store JDBC user and password in the credential fields, not in the URL");
    }
}
