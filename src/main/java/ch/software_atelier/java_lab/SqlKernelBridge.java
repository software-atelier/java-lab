package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** Private loopback transport between remote JShell JVMs and the shared JDBC/Maven service. */
final class SqlKernelBridge implements AutoCloseable {
    private final SqlService sql;
    private final ObjectMapper json = new ObjectMapper();
    private final String token = UUID.randomUUID().toString();
    private HttpServer server;
    private ExecutorService executor;

    SqlKernelBridge(SqlService sql) { this.sql = sql; }

    synchronized String endpoint() {
        if (sql == null) return "";
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                executor = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "java-lab-sql");t.setDaemon(true);return t; });
                server.setExecutor(executor);
                server.createContext("/" + token, exchange -> {
                    try (exchange) {
                        if (!exchange.getRequestURI().getPath().equals("/" + token) || !exchange.getRequestMethod().equals("POST")) {
                            exchange.sendResponseHeaders(404, -1);return;
                        }
                        int status = 200;Object body;
                        try {
                            byte[] bytes = exchange.getRequestBody().readNBytes(4097);
                            if (bytes.length > 4096) throw new IOException("SQL path is too long");
                            var request = json.readTree(bytes);
                            body = sql.load(request.path("path").asText(""));
                        } catch (ResponseStatusException e) {
                            status = 400;body = Map.of("error", e.getReason() == null ? "SQL load failed" : e.getReason());
                        } catch (Exception e) {
                            status = 400;body = Map.of("error", "Could not load SQL file. Check the saved file and connection settings.");
                        }
                        byte[] bytes = json.writeValueAsBytes(body);
                        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
                server.start();
            } catch (IOException e) { close();throw new IllegalStateException("Could not start notebook SQL helper", e); }
        }
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + token;
    }

    @Override public synchronized void close() {
        if (server != null) { server.stop(0);server = null; }
        if (executor != null) { executor.shutdownNow();executor = null; }
    }
}
