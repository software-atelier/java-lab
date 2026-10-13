package ch.software_atelier.java_lab;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises Maven settings without depending on a user's ~/.m2 or the public internet. */
@Timeout(30)
class MavenDependenciesSettingsTest {
    @TempDir Path directory;

    @Test void resolvesTransitiveJarsFromActiveProfileRepository() throws Exception {
        Path repository = directory.resolve("repository");
        writeArtifacts(repository);
        Path localRepository = directory.resolve("configured-local-repository");
        Path settings = writeSettings("""
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <localRepository>%s</localRepository>
                  <profiles><profile><id>private-repo</id><repositories><repository>
                    <id>fixture</id><url>%s</url>
                  </repository></repositories></profile></profiles>
                  <activeProfiles><activeProfile>private-repo</activeProfile></activeProfiles>
                </settings>
                """.formatted(localRepository, repository.toUri()));

        List<Path> jars = resolver(settings).resolve(List.of("test.fixture:parent:1.0"));

        assertThat(jars).extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrder("parent-1.0.jar", "child-1.0.jar");
        assertThat(jars).allMatch(path -> path.startsWith(localRepository));
    }

    @Test void appliesMirrorAndAuthenticatesPlainAndEncryptedServerPassword() throws Exception {
        Path repository = directory.resolve("repository");
        writeArtifacts(repository);
        String password = "fixture-secret-not-for-errors";
        AtomicInteger authorizedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "maven-fixture-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/repo/", exchange -> {
            String expected = "Basic " + Base64.getEncoder().encodeToString(("fixture-user:" + password).getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=fixture");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            authorizedRequests.incrementAndGet();
            Path artifact = repository.resolve(exchange.getRequestURI().getPath().substring("/repo/".length())).normalize();
            if (!artifact.startsWith(repository) || !Files.isRegularFile(artifact)) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] bytes = Files.readAllBytes(artifact);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
            }
            exchange.close();
        });
        server.start();
        try {
            Path settings = mirrorSettings(server.getAddress().getPort(), password);
            List<Path> jars = resolver(settings).resolve(List.of("test.fixture:parent:1.0"));
            assertThat(jars).hasSize(2);
            assertThat(authorizedRequests.get()).isPositive();

            // Maven encrypts the master with the fixed dispatcher passphrase,
            // then encrypts repository passwords with that master.
            var cipher = new DefaultPlexusCipher();
            String master = "fixture-master-secret";
            String encryptedMaster = cipher.encryptAndDecorate(master, "settings.security");
            String encryptedPassword = cipher.encryptAndDecorate(password, master);
            Files.writeString(directory.resolve("settings-security.xml"),
                    "<settingsSecurity><master>" + encryptedMaster + "</master></settingsSecurity>");
            int beforeEncrypted = authorizedRequests.get();
            List<Path> encryptedJars = resolver(mirrorSettings(server.getAddress().getPort(), encryptedPassword))
                    .resolve(List.of("test.fixture:parent:1.0"));
            assertThat(encryptedJars).hasSize(2);
            assertThat(authorizedRequests.get()).isGreaterThan(beforeEncrypted);
        } finally {
            server.stop(0);
        }
    }

    @Test void resolutionFailureDoesNotExposeSettingsCredential() throws Exception {
        String password = "secret-must-not-appear-in-error";
        Path settings = writeSettings("""
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <profiles><profile><id>private-repo</id><repositories><repository>
                    <id>private</id><url>http://127.0.0.1:1/unreachable/</url>
                  </repository></repositories></profile></profiles>
                  <activeProfiles><activeProfile>private-repo</activeProfile></activeProfiles>
                  <servers><server><id>private</id><username>fixture-user</username>
                    <password>%s</password></server></servers>
                </settings>
                """.formatted(password));

        assertThatThrownBy(() -> resolver(settings).resolve(List.of("test.fixture:missing:1.0")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageNotContaining(password)
                .hasMessageNotContaining("fixture-user");
    }

    private MavenDependencies resolver(Path settings) {
        return new MavenDependencies(settings, null, directory.resolve("cache-" + System.nanoTime()));
    }

    private Path writeSettings(String xml) throws IOException {
        Path settings = Files.createTempFile(directory, "settings-", ".xml");
        Files.writeString(settings, xml);
        return settings;
    }

    private Path mirrorSettings(int port, String password) throws IOException {
        return writeSettings("""
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <profiles><profile><id>private-repo</id><repositories><repository>
                    <id>upstream</id><url>http://127.0.0.1:1/unreachable/</url>
                  </repository></repositories></profile></profiles>
                  <activeProfiles><activeProfile>private-repo</activeProfile></activeProfiles>
                  <mirrors><mirror><id>private-mirror</id><mirrorOf>upstream</mirrorOf>
                    <url>http://127.0.0.1:%d/repo/</url></mirror></mirrors>
                  <servers><server><id>private-mirror</id><username>fixture-user</username>
                    <password>%s</password></server></servers>
                </settings>
                """.formatted(port, password));
    }

    private static void writeArtifacts(Path repository) throws Exception {
        writeArtifact(repository, "parent", """
                <dependencies><dependency><groupId>test.fixture</groupId><artifactId>child</artifactId>
                <version>1.0</version><scope>runtime</scope></dependency></dependencies>
                """);
        writeArtifact(repository, "child", "");
    }

    private static void writeArtifact(Path repository, String artifactId, String dependencies) throws Exception {
        Path base = repository.resolve("test/fixture/" + artifactId + "/1.0");
        Files.createDirectories(base);
        Path pom = base.resolve(artifactId + "-1.0.pom");
        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>test.fixture</groupId><artifactId>%s</artifactId><version>1.0</version>
                %s</project>
                """.formatted(artifactId, dependencies));
        Path jarPath = base.resolve(artifactId + "-1.0.jar");
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            jar.write("Manifest-Version: 1.0\n\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        writeSha1(pom);
        writeSha1(jarPath);
    }

    private static void writeSha1(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file));
        Files.writeString(file.resolveSibling(file.getFileName() + ".sha1"), HexFormat.of().formatHex(digest));
    }
}
