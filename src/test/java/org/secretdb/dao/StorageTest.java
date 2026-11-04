package org.secretdb.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.secretdb.dao.impl.*;
import org.secretdb.dao.model.Secret;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @TempDir Path dir;
    private Secret secret(String name, String value) {
        return Secret.builder().name(name).value(value).private_key_hash("hash").build();
    }

    @Test void factorySelectsOneSharedSqliteInstance() {
        String home = System.getProperty("user.home");
        String storage = System.getProperty("secretdb.storage");
        try {
            System.setProperty("user.home", dir.toString());
            System.setProperty("secretdb.storage", "sqlite");
            SecretDBFactory factory = new SecretDBFactory(new ObjectMapper());
            SecretDB a = factory.getSecretDB("a", SecretDBFactory.DB_MODE.WRITE);
            SecretDB b = factory.getSecretDB("b", SecretDBFactory.DB_MODE.READ);
            assertInstanceOf(SQLiteSecretDB.class, a);
            assertSame(a, b);
            a.write("a", secret("name", "cipher"));
            assertEquals("cipher", b.get("a", "name").orElseThrow().getValue());
        } finally {
            System.setProperty("user.home", home);
            if (storage == null) System.clearProperty("secretdb.storage"); else System.setProperty("secretdb.storage", storage);
        }
    }

    @Test void bothBackendsPreserveIsolationAndConditionalDelete() {
        for (SecretDB db : List.of(new SQLiteSecretDB(dir.resolve("db.sqlite")), new OnDiskSecretDB(new ObjectMapper(), dir.resolve("files")))) {
            String name = "引号'\"; DROP TABLE secrets; --";
            assertTrue(db.get("a", name).isEmpty());
            assertTrue(db.list("a").isEmpty());
            Secret old = secret(name, "cipher-old");
            db.write("a", old);
            db.write("b", secret(name, "other-tenant"));
            db.write("a", secret("keep", "keep-value"));
            Secret updated = secret(name, "cipher-new");
            db.write("a", updated);
            assertEquals(2, db.list("a").size());
            assertFalse(db.delete("a", old), "stale authenticated snapshot must not delete a replacement");
            assertEquals("cipher-new", db.get("a", name).orElseThrow().getValue());
            assertTrue(db.delete("a", updated));
            assertFalse(db.delete("a", updated));
            assertEquals("other-tenant", db.get("b", name).orElseThrow().getValue());
            assertEquals("keep-value", db.get("a", "keep").orElseThrow().getValue());
        }
    }

    @Test void persistsAcrossInstancesAndBacksUpConsistently() throws Exception {
        Path path = dir.resolve("live.sqlite");
        SQLiteSecretDB db = new SQLiteSecretDB(path);
        db.write("a", secret("name", "encrypted-value"));
        Path backup = dir.resolve("backup/snapshot.sqlite");
        SQLiteSecretDB.backup(path, backup);
        db.write("a", secret("name", "new-value"));
        assertEquals("new-value", new SQLiteSecretDB(path).get("a", "name").orElseThrow().getValue());
        assertEquals("encrypted-value", new SQLiteSecretDB(backup).get("a", "name").orElseThrow().getValue());
        SQLiteSecretDB.backup(path, backup);
        assertEquals("new-value", new SQLiteSecretDB(backup).get("a", "name").orElseThrow().getValue());
        assertThrows(java.io.IOException.class, () -> SQLiteSecretDB.backup(dir.resolve("missing"), backup));
        assertEquals("new-value", new SQLiteSecretDB(backup).get("a", "name").orElseThrow().getValue());
    }

    @Test void concurrentWritesDoNotLoseRecords() throws Exception {
        Path path = dir.resolve("concurrent.sqlite");
        SQLiteSecretDB first = new SQLiteSecretDB(path);
        SQLiteSecretDB second = new SQLiteSecretDB(path);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                int id = i;
                work.add(pool.submit(() -> (id % 2 == 0 ? first : second).write("tenant", secret("n" + id, "v" + id))));
            }
            for (Future<?> future : work) future.get(30, TimeUnit.SECONDS);
        }
        assertEquals(80, first.list("tenant").size());
    }

    @Test void migrationPreservesCiphertextAndRejectsOverwrite() throws Exception {
        Path source = dir.resolve("legacy");
        Files.createDirectories(source);
        ObjectMapper mapper = new ObjectMapper();
        String original = mapper.writeValueAsString(secret("name", "unchanged-ciphertext")) + "\n";
        Files.writeString(source.resolve("tenant"), original);
        SQLiteSecretDB db = new SQLiteSecretDB(dir.resolve("migrated.sqlite"));
        assertEquals(1, db.importDirectory(source, mapper));
        assertEquals("unchanged-ciphertext", db.get("tenant", "name").orElseThrow().getValue());
        assertEquals(original, Files.readString(source.resolve("tenant")));
        assertThrows(SQLException.class, () -> db.importDirectory(source, mapper));
        assertEquals(1, db.list("tenant").size());
    }

    @Test void malformedMigrationRollsBackEarlierRecords() throws Exception {
        Path source = dir.resolve("legacy");
        Files.createDirectories(source);
        ObjectMapper mapper = new ObjectMapper();
        Files.writeString(source.resolve("tenant"), mapper.writeValueAsString(secret("good", "cipher")) + "\nnot-json\n");
        SQLiteSecretDB db = new SQLiteSecretDB(dir.resolve("rollback.sqlite"));
        assertThrows(java.io.IOException.class, () -> db.importDirectory(source, mapper));
        assertTrue(db.list("tenant").isEmpty());
    }
}
