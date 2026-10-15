package ch.software_atelier.java_lab;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@org.springframework.boot.context.properties.EnableConfigurationProperties(ch.software_atelier.java_lab.ai.AiProperties.class)
public class JavaLabApplication {
    private static final Logger log = LoggerFactory.getLogger(JavaLabApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(JavaLabApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser(ApplicationReadyEvent event) {
        if (GraphicsEnvironment.isHeadless()
                || !(event.getApplicationContext() instanceof ServletWebServerApplicationContext context)) {
            return;
        }

        String address = context.getEnvironment().getProperty("server.address", "127.0.0.1");
        boolean secure = context.getEnvironment().getProperty("server.ssl.enabled", Boolean.class, false);
        String contextPath = context.getServletContext().getContextPath();
        URI url;
        try {
            url = browserUri(context.getWebServer().getPort(), address, contextPath, secure);
        } catch (URISyntaxException e) {
            log.warn("Cannot construct the local browser URL", e);
            return;
        }

        Thread browserThread = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(url);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Could not open Java Lab in the default browser at {}", url, e);
            }
        }, "java-lab-browser-launcher");
        browserThread.setDaemon(true);
        browserThread.start();
    }

    static URI browserUri(int port, String address, String contextPath, boolean secure) throws URISyntaxException {
        String host = address == null || address.isBlank() || address.equals("0.0.0.0")
                || address.equals("::") || address.equals("[::]") ? "127.0.0.1" : address;
        String path = contextPath == null || contextPath.isBlank() || contextPath.equals("/")
                ? "/" : contextPath + "/";
        return new URI(secure ? "https" : "http", null, host, port, path, null, null);
    }
}
