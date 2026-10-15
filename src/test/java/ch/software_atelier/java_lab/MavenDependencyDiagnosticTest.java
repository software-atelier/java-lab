package ch.software_atelier.java_lab;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.http.client.HttpResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;

import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenDependencyDiagnosticTest {
    @TempDir Path directory;

    @Test void failedResolutionLogsSafeDiagnosticAtDefaultLevel() throws Exception {
        Path settings = directory.resolve("settings.xml");
        Path repository = directory.resolve("secret-password");
        Files.createDirectories(repository);
        Files.writeString(settings, """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <mirrors><mirror><id>fixture</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors>
                </settings>
                """.formatted(repository.toUri()));
        Logger logger = (Logger) LoggerFactory.getLogger(MavenDependencies.class);
        Level oldLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            assertThatThrownBy(() -> new MavenDependencies(settings, null, directory.resolve("cache"))
                    .resolve(List.of("test.fixture:missing:1.0")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageNotContaining("secret-password");
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allMatch(event -> event.getLevel() == Level.WARN);
            String diagnostic = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + b);
            assertThat(diagnostic).contains("Maven dependency", "ArtifactNotFoundException")
                    .doesNotContain("secret-password", "file:/", repository.toString());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(oldLevel);
            appender.stop();
        }
    }

    @Test void reportsHttpStatusAndCauseTypesWithoutThrowableMessages() {
        String secret = "secret-user:secret-password@private.example/repo?token=secret-token";
        var failure = new RuntimeException("https://" + secret,
                new HttpResponseException(407, "Basic " + secret));
        failure.addSuppressed(new UnknownHostException(secret));

        String diagnostic = MavenDependencies.failureDiagnostic(failure);

        assertThat(diagnostic).contains("HTTP 407", "UnknownHostException", "OtherException")
                .doesNotContain("secret-user", "secret-password", "secret-token", "private.example", "Basic");
    }

    @Test void boundsAndDeduplicatesFailureGraph() {
        var failure = new RuntimeException("sensitive top-level message");
        var cycle = new RuntimeException("sensitive nested message");
        failure.addSuppressed(cycle);
        cycle.addSuppressed(failure);
        for (int i = 0; i < 50; i++) failure.addSuppressed(new RuntimeException("secret-" + i));

        String diagnostic = MavenDependencies.failureDiagnostic(failure);

        assertThat(diagnostic.split(" -> ")).hasSize(30);
        assertThat(diagnostic).doesNotContain("secret");
    }
}
