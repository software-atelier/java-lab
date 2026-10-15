package ch.software_atelier.java_lab;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
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
import java.util.Map;
import java.util.UUID;
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

    @Test void resolvesFromLocalCacheWithoutSettingsOrSecurityFile() throws Exception {
        Path missingSettings = directory.resolve("missing-settings.xml");
        Path cache = directory.resolve("cache-without-settings");
        writeArtifacts(cache);
        assertThat(Files.exists(missingSettings)).isFalse();
        assertThat(Files.exists(directory.resolve("settings-security.xml"))).isFalse();

        List<Path> jars = new MavenDependencies(missingSettings, null, cache)
                .resolve(List.of("test.fixture:parent:1.0"));

        assertThat(jars).extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrder("parent-1.0.jar", "child-1.0.jar");
    }

    @Test void encryptedPasswordWithoutSecurityFileFailsWithoutLeakingCredential() throws Exception {
        String encryptedPassword = new DefaultPlexusCipher().encryptAndDecorate("private-secret", "master-secret");
        Path settings = writeSettings("""
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <servers><server><id>private</id><username>fixture-user</username>
                    <password>%s</password></server></servers>
                </settings>
                """.formatted(encryptedPassword));

        assertThatThrownBy(() -> resolver(settings).resolve(List.of("test.fixture:missing:1.0")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Could not decrypt Maven settings credentials")
                .hasMessageNotContaining(encryptedPassword)
                .hasMessageNotContaining("private-secret");
    }

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

    @Test void missingEnvironmentVariableInMirrorUrlHasActionableSafeError() throws Exception {
        String variable = absentEnvironmentVariable();
        Path settings = writeSettings("""
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <mirrors><mirror><id>private-mirror</id><mirrorOf>*</mirrorOf>
                    <url>https://${env.%s}/repository/</url></mirror></mirrors>
                </settings>
                """.formatted(variable));

        assertThatThrownBy(() -> resolver(settings).resolve(List.of("test.fixture:missing:1.0")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(variable)
                .hasMessageContaining("environment")
                .hasMessageNotContaining("${env.");
    }

    @Test void missingEnvironmentVariableInCredentialsHasActionableSafeError() throws Exception {
        String variable = absentEnvironmentVariable();
        Path settings = writeSettings("""
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <mirrors><mirror><id>private-mirror</id><mirrorOf>*</mirrorOf>
                    <url>http://127.0.0.1:1/unreachable/</url></mirror></mirrors>
                  <servers><server><id>private-mirror</id><username>fixture-user</username>
                    <password>${env.%s}</password></server></servers>
                </settings>
                """.formatted(variable));

        assertThatThrownBy(() -> resolver(settings).resolve(List.of("test.fixture:missing:1.0")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(variable)
                .hasMessageContaining("environment")
                .hasMessageNotContaining("fixture-user");
    }

    @Test void unauthorizedMirrorHasActionableSafeErrorWithoutCredentialsOrUrl() throws Exception {
        String password = "fixture-secret-not-for-errors";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repo/", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=fixture");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            Path settings = mirrorSettings(server.getAddress().getPort(), password);
            assertThatThrownBy(() -> resolver(settings).resolve(List.of("test.fixture:missing:1.0")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("authentication failed")
                    .hasMessageContaining("server ID")
                    .hasMessageNotContaining("fixture-user")
                    .hasMessageNotContaining(password)
                    .hasMessageNotContaining("127.0.0.1");
        } finally {
            server.stop(0);
        }
    }

    @Test void interpolatesEnvironmentPortBeforeParsingAndRoutesThroughProxy() throws Exception {
        Path repository = directory.resolve("proxy-repository");
        writeArtifacts(repository);
        AtomicInteger directRequests = new AtomicInteger();
        AtomicInteger proxyRequests = new AtomicInteger();
        HttpServer direct = artifactServer(repository, directRequests);
        HttpServer proxy = artifactServer(repository, proxyRequests);
        try {
            Path settings = proxySettings(direct.getAddress().getPort(), "${env.PROXY_PORT}", "");
            var resolver = new MavenDependencies(settings, null, directory.resolve("proxy-cache"),
                    Map.of("PROXY_PORT", Integer.toString(proxy.getAddress().getPort())));
            assertThat(resolver.resolve(List.of("test.fixture:parent:1.0"))).hasSize(2);
            assertThat(proxyRequests.get()).isPositive();
            assertThat(directRequests.get()).isZero();
        } finally {
            proxy.stop(0);
            direct.stop(0);
        }
    }

    @Test void globalSettingsProxyRespectsNonProxyHosts() throws Exception {
        Path repository = directory.resolve("direct-repository");
        writeArtifacts(repository);
        AtomicInteger directRequests = new AtomicInteger();
        AtomicInteger proxyRequests = new AtomicInteger();
        HttpServer direct = artifactServer(repository, directRequests);
        HttpServer proxy = artifactServer(repository, proxyRequests);
        try {
            Path globalSettings = proxySettings(direct.getAddress().getPort(), "${env.PROXY_PORT}",
                    "127.0.0.1");
            var resolver = new MavenDependencies(null, globalSettings, directory.resolve("bypass-cache"),
                    Map.of("PROXY_PORT", Integer.toString(proxy.getAddress().getPort())));
            assertThat(resolver.resolve(List.of("test.fixture:parent:1.0"))).hasSize(2);
            assertThat(directRequests.get()).isPositive();
            assertThat(proxyRequests.get()).isZero();
        } finally {
            proxy.stop(0);
            direct.stop(0);
        }
    }

    @Test void rejectsInvalidActiveProxyPortsWithoutLeakingCredentials() throws Exception {
        for (String port : List.of("0", "65536", "not-a-port", "${env.MISSING_PROXY_PORT}")) {
            Path settings = proxySettings(1, port, "");
            assertThatThrownBy(() -> resolver(settings).resolve(List.of("test.fixture:missing:1.0")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Invalid active Maven proxy settings")
                    .hasMessageNotContaining("private-proxy-password")
                    .hasMessageNotContaining("proxy-user");
        }
    }

    @Test void interpolationEscapesXmlSpecialCharacters() {
        assertThat(MavenDependencies.interpolateSettingsXml("<password>${env.SECRET}</password>",
                Map.of("SECRET", "a&<\"'b"), System.getProperties()))
                .isEqualTo("<password>a&amp;&lt;&quot;&apos;b</password>");
    }

    @Test void readsXmlDeclaredEncodingBeforeInterpolation() throws Exception {
        Path repository = directory.resolve("latin1-repository");
        writeArtifacts(repository);
        Path localRepository = directory.resolve("café-cache");
        Path settings = directory.resolve("latin1-settings.xml");
        String xml = """
                <?xml version="1.0" encoding="ISO-8859-1"?>
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <localRepository>%s</localRepository>
                  <profiles><profile><id>répo</id><repositories><repository>
                    <id>fixture</id><url>%s</url>
                  </repository></repositories></profile></profiles>
                  <activeProfiles><activeProfile>répo</activeProfile></activeProfiles>
                </settings>
                """.formatted(localRepository, repository.toUri());
        Files.write(settings, xml.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        List<Path> jars = resolver(settings).resolve(List.of("test.fixture:parent:1.0"));
        assertThat(jars).hasSize(2).allMatch(path -> path.startsWith(localRepository));
    }

    @Test void rejectsUnresolvedActiveProxyCredentialsWithoutLeakingValues() throws Exception {
        for (String field : List.of("username", "password")) {
            Path settings = proxySettings(1, "8888", "");
            String xml = Files.readString(settings).replace(
                    "<" + field + ">" + (field.equals("username") ? "proxy-user" : "private-proxy-password")
                            + "</" + field + ">",
                    "<" + field + ">${env.MISSING_PROXY_CREDENTIAL}</" + field + ">");
            Files.writeString(settings, xml);
            assertThatThrownBy(() -> resolver(settings).resolve(List.of("test.fixture:missing:1.0")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Invalid active Maven proxy settings")
                    .hasMessageNotContaining("private-proxy-password")
                    .hasMessageNotContaining("proxy-user")
                    .hasMessageNotContaining("${env.");
        }
    }

    @Test void rejectsPlaceholderRevealedByDecryptingProxyPassword() throws Exception {
        var cipher = new DefaultPlexusCipher();
        String master = "fixture-proxy-master";
        Files.writeString(directory.resolve("settings-security.xml"), "<settingsSecurity><master>"
                + cipher.encryptAndDecorate(master, "settings.security") + "</master></settingsSecurity>");
        String encrypted = cipher.encryptAndDecorate("${env.MISSING_PROXY_CREDENTIAL}", master);
        Path settings = proxySettings(1, "8888", "");
        Files.writeString(settings, Files.readString(settings).replace("private-proxy-password", encrypted));

        assertThatThrownBy(() -> resolver(settings).resolve(List.of("test.fixture:missing:1.0")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid active Maven proxy settings")
                .hasMessageNotContaining(encrypted)
                .hasMessageNotContaining("${env.");
    }

    private Path proxySettings(int repositoryPort, String proxyPort, String nonProxyHosts) throws IOException {
        return writeSettings("""
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <profiles><profile><id>fixture</id><repositories><repository><id>fixture</id>
                    <url>http://127.0.0.1:%d/repo/</url>
                  </repository></repositories></profile></profiles>
                  <activeProfiles><activeProfile>fixture</activeProfile></activeProfiles>
                  <proxies><proxy><id>fixture-proxy</id><active>true</active>
                    <protocol>http</protocol><host>127.0.0.1</host><port>%s</port>
                    <nonProxyHosts>%s</nonProxyHosts><username>proxy-user</username>
                    <password>private-proxy-password</password>
                  </proxy></proxies>
                </settings>
                """.formatted(repositoryPort, proxyPort, nonProxyHosts));
    }

    private static HttpServer artifactServer(Path repository, AtomicInteger requests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> serveArtifact(exchange, repository, requests));
        server.start();
        return server;
    }

    private static void serveArtifact(HttpExchange exchange, Path repository, AtomicInteger requests) throws IOException {
        requests.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        Path artifact = path.startsWith("/repo/")
                ? repository.resolve(path.substring("/repo/".length())).normalize() : repository.resolve("missing");
        if (!artifact.startsWith(repository) || !Files.isRegularFile(artifact)) {
            exchange.sendResponseHeaders(404, -1);
        } else {
            byte[] bytes = Files.readAllBytes(artifact);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        }
        exchange.close();
    }

    private static String absentEnvironmentVariable() {
        String name = "JAVA_LAB_TEST_MISSING_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(System.getenv(name)).isNull();
        return name;
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
