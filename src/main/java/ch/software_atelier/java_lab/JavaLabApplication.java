package ch.software_atelier.java_lab;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

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
        SpringApplication application = new SpringApplication(JavaLabApplication.class);
        // Spring Boot forces AWT headless mode by default, even on a desktop.
        application.setHeadless(false);
        application.run(args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof ServletWebServerApplicationContext context)) {
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
        log.info("Java Lab is available at {}", url);
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        boolean mac = osName.contains("mac");
        boolean linux = osName.contains("linux");
        boolean linuxWithoutDisplay = linux
                && System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null;
        // AWT may report headless on Wayland even though xdg-open has a desktop session.
        if (linux ? linuxWithoutDisplay : GraphicsEnvironment.isHeadless()) {
            log.info("No graphical desktop detected; open {} in a browser manually", url);
            return;
        }

        Thread browserThread = new Thread(() -> {
            try {
                if (mac) {
                    // macOS Launch Services reliably uses the user's default browser.
                    openWithCommand("/usr/bin/open", url);
                } else if (linux) {
                    // xdg-open works with X11 and Wayland without initializing AWT.
                    openWithCommand("xdg-open", url);
                } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(url);
                    log.info("Opened Java Lab in the default browser at {}", url);
                } else {
                    log.warn("Automatic browser opening is unavailable; open {} manually", url);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Could not open Java Lab in the default browser at {}", url, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Browser opening was interrupted; open {} manually", url);
            }
        }, "java-lab-browser-launcher");
        browserThread.setDaemon(true);
        browserThread.start();
    }

    private static void openWithCommand(String command, URI url) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command, url.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("Could not open Java Lab in the default browser at {} ({} exited with {}: {})",
                    url, command, exitCode, output);
        } else {
            log.info("Opened Java Lab in the default browser at {}", url);
        }
    }

    static URI browserUri(int port, String address, String contextPath, boolean secure) throws URISyntaxException {
        String host = address == null || address.isBlank() || address.equals("0.0.0.0")
                || address.equals("::") || address.equals("[::]") ? "127.0.0.1" : address;
        String path = contextPath == null || contextPath.isBlank() || contextPath.equals("/")
                ? "/" : contextPath + "/";
        return new URI(secure ? "https" : "http", null, host, port, path, null, null);
    }
}
