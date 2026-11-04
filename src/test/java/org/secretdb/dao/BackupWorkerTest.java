package org.secretdb.dao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.secretdb.dao.impl.SQLiteSecretDB;
import org.secretdb.dao.model.Secret;
import org.secretdb.scheduled.BackupWorker;
import java.nio.file.*;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class BackupWorkerTest {
    @TempDir Path root;

    @Test void addsSqliteSnapshotEvenAfterTodaysLegacyBackup() throws Exception {
        Path legacy = root.resolve("data");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("tenant"), "legacy encrypted record");
        BackupWorker worker = new BackupWorker(root);
        worker.run();
        Path dated = root.resolve("backup").resolve(LocalDate.now().toString());
        assertEquals("legacy encrypted record", Files.readString(dated.resolve("tenant")));
        Path dbPath = root.resolve("sqlite/secrets.db");
        SQLiteSecretDB db = new SQLiteSecretDB(dbPath);
        db.write("t", Secret.builder().name("n").value("cipher").private_key_hash("hash").build());
        worker.run();
        assertEquals("cipher", new SQLiteSecretDB(dated.resolve("sqlite/secrets.db")).get("t", "n").orElseThrow().getValue());
        assertEquals(LocalDate.now().toString(), Files.readString(root.resolve("backup/last_modify_date")).strip());
    }

    @Test void failedSqliteBackupDoesNotMarkDayCompleteAndCanRetry() throws Exception {
        Path source = root.resolve("sqlite/secrets.db");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "not a SQLite database");
        BackupWorker worker = new BackupWorker(root);
        worker.run();
        assertFalse(Files.exists(root.resolve("backup/last_modify_date")));
        Files.delete(source);
        SQLiteSecretDB db = new SQLiteSecretDB(source);
        db.write("t", Secret.builder().name("n").value("cipher").private_key_hash("hash").build());
        worker.run();
        assertTrue(Files.isRegularFile(root.resolve("backup/last_modify_date")));
        assertTrue(Files.isRegularFile(root.resolve("backup").resolve(LocalDate.now().toString()).resolve("sqlite/secrets.db")));
    }
}
