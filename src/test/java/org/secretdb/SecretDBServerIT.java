package org.secretdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.secretdb.dao.impl.SQLiteSecretDB;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real shaded JAR, child JVMs, TCP requests, encryption, migration and persistent storage. */
@Timeout(120)
class SecretDBServerIT {
    @TempDir Path home;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path JAR = Path.of("target/SecretDB-1.0-SNAPSHOT.jar").toAbsolutePath();
    private static final Path LOG_CONFIG = Path.of("src/test/resources/log4j2-test.xml").toAbsolutePath();
    private static final String KEY = "test-key-only";

    @Test void fileToSqliteMigrationPreservesApiBehaviorAndDeletePersistsAfterRestart() throws Exception {
        String name = "record 'quoted' \\";
        try (Server server = new Server("file")) {
            assertEquals(200, server.call("PUT", "a", name, KEY, "original").statusCode());
            assertEquals(200, server.call("PUT", "b", name, KEY, "other tenant").statusCode());
            assertEquals("original", server.call("POST", "a", name, KEY, null).body());
            assertTrue(server.list("a").contains(name));
        }
        Path legacy = home.resolve("secret_db/data/a");
        String source = Files.readString(legacy);
        assertFalse(source.contains("original"), "file backend must store ciphertext");
        assertEquals(0, migrate());
        assertEquals(source, Files.readString(legacy), "migration must preserve original files");
        try (Server server = new Server("sqlite")) {
            assertEquals("original", server.call("POST", "a", name, KEY, null).body());
            assertEquals("other tenant", server.call("POST", "b", name, KEY, null).body());
            assertEquals(200, server.call("PUT", "a", name, KEY, "updated").statusCode());
            assertEquals(403, server.call("DELETE", "a", name, "wrong", null).statusCode());
            assertEquals("updated", server.call("POST", "a", name, KEY, null).body());
            assertEquals(204, server.call("DELETE", "a", name, KEY, null).statusCode());
            assertEquals(404, server.call("POST", "a", name, KEY, null).statusCode());
            assertEquals(404, server.call("DELETE", "a", name, KEY, null).statusCode());
            assertFalse(server.list("a").contains(name));
        }
        try (Server server = new Server("sqlite")) {
            assertEquals(404, server.call("POST", "a", name, KEY, null).statusCode());
            assertEquals("other tenant", server.call("POST", "b", name, KEY, null).body());
        }
        assertEquals(1, migrate(), "existing destination records must reject repeat import");
        assertTrue(new SQLiteSecretDB(database()).get("a", name).isEmpty(), "conflict must roll back the entire import");
    }

    @Test void sqliteSnapshotCanRestoreDeletedRecordThroughHttp() throws Exception {
        Path snapshot = home.resolve("snapshots/secrets.db");
        try (Server server = new Server("sqlite")) {
            assertEquals(200, server.call("PUT", "tenant", "restore-me", KEY, "before backup").statusCode());
            SQLiteSecretDB.backup(database(), snapshot);
            assertEquals(204, server.call("DELETE", "tenant", "restore-me", KEY, null).statusCode());
        }
        Files.copy(snapshot, database(), StandardCopyOption.REPLACE_EXISTING);
        try (Server server = new Server("sqlite")) {
            assertEquals("before backup", server.call("POST", "tenant", "restore-me", KEY, null).body());
            assertEquals(400, server.raw("DELETE", "/api/tenant", "not-json").statusCode());
            assertEquals(400, server.raw("DELETE", "/api/tenant", "{}").statusCode());
            assertEquals("before backup", server.call("POST", "tenant", "restore-me", KEY, null).body());
        }
    }

    @Test void legacyDeleteAlsoSurvivesRestart() throws Exception {
        try (Server server = new Server("file")) {
            server.call("PUT", "tenant", "delete-me", KEY, "secret");
            server.call("PUT", "tenant", "keep-me", KEY, "keep");
            assertEquals(403, server.call("DELETE", "tenant", "delete-me", "wrong", null).statusCode());
            assertEquals(204, server.call("DELETE", "tenant", "delete-me", KEY, null).statusCode());
        }
        try (Server server = new Server("file")) {
            assertEquals(404, server.call("POST", "tenant", "delete-me", KEY, null).statusCode());
            assertEquals("keep", server.call("POST", "tenant", "keep-me", KEY, null).body());
        }
    }

    private Path database() { return home.resolve("secret_db/sqlite/secrets.db"); }

    private List<String> javaCommand() {
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home, "-Dlog4j.configurationFile=" + LOG_CONFIG));
    }

    private int migrate() throws Exception {
        List<String> command = javaCommand();
        command.addAll(List.of("-cp", JAR.toString(), "org.secretdb.dao.ImportLegacy",
                home.resolve("secret_db/data").toString(), database().toString()));
        Path log = Files.createTempFile(home, "migration-", ".log");
        Process child = new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Migration timed out: " + log);
            return child.exitValue();
        } finally { stop(child); }
    }

    private static void stop(Process child) throws InterruptedException {
        if (child.isAlive()) {
            child.destroy();
            if (!child.waitFor(5, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Child JVM did not terminate");
            }
        }
    }

    private final class Server implements AutoCloseable {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        private final Process child;
        private final String base;

        Server(String backend) throws Exception {
            int port;
            try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
            base = "http://127.0.0.1:" + port;
            List<String> command = javaCommand();
            command.addAll(List.of("-Dsecretdb.storage=" + backend, "-jar", JAR.toString(), String.valueOf(port)));
            Path log = Files.createTempFile(home, "server-", ".log");
            child = new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
                while (child.isAlive() && System.nanoTime() < deadline) {
                    try { if (raw("GET", "/api/list/health", null).statusCode() == 200) return; }
                    catch (IOException ignored) { /* wait for socket binding */ }
                    Thread.sleep(100);
                }
                throw new IOException("Server failed to start: " + Files.readString(log));
            } catch (Exception | Error e) { close(); throw e; }
        }

        HttpResponse<String> call(String method, String tenant, String name, String key, String value) throws Exception {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("name", name); payload.put("key", key);
            if (value != null) payload.put("value", value);
            return raw(method, "/api/" + tenant, JSON.writeValueAsString(payload));
        }

        String list(String tenant) throws Exception {
            HttpResponse<String> response = raw("GET", "/api/list/" + tenant, null);
            assertEquals(200, response.statusCode());
            return response.body();
        }

        HttpResponse<String> raw(String method, String path, String body) throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        public void close() throws InterruptedException {
            try { stop(child); } finally { http.close(); }
        }
    }
}
