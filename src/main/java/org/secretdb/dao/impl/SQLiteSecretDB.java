package org.secretdb.dao.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.secretdb.dao.SecretDB;
import org.secretdb.dao.model.Secret;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Stores the already-encrypted values supplied by the servlet. No plaintext keys are stored here. */
public final class SQLiteSecretDB implements SecretDB {
    private final Path path;
    private static final String INSERT = "INSERT INTO secrets(tenant_id,name,value,private_key_hash) VALUES(?,?,?,?)";

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), "secret_db", "sqlite", "secrets.db");
    }

    public SQLiteSecretDB(Path path) {
        this.path = path.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.path.getParent());
            try (Connection c = open(this.path, false); Statement s = c.createStatement()) {
                s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS secrets (
                        tenant_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        value TEXT NOT NULL,
                        private_key_hash TEXT NOT NULL,
                        PRIMARY KEY (tenant_id, name)
                    )
                    """);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Cannot initialize SQLite database", e);
        }
    }

    private static Connection open(Path path, boolean readOnly) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(10000);
        config.setReadOnly(readOnly);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        return config.createConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    @Override
    public List<Secret> list(String tenantId) {
        try (Connection c = open(path, true);
             PreparedStatement s = c.prepareStatement("SELECT name,value,private_key_hash FROM secrets WHERE tenant_id=? ORDER BY rowid")) {
            s.setString(1, tenantId);
            try (ResultSet r = s.executeQuery()) {
                List<Secret> result = new ArrayList<>();
                while (r.next()) result.add(record(r));
                return result;
            }
        } catch (SQLException e) { throw new IllegalStateException("Cannot list secrets", e); }
    }

    @Override
    public Optional<Secret> get(String tenantId, String name) {
        try (Connection c = open(path, true);
             PreparedStatement s = c.prepareStatement("SELECT name,value,private_key_hash FROM secrets WHERE tenant_id=? AND name=?")) {
            s.setString(1, tenantId); s.setString(2, name);
            try (ResultSet r = s.executeQuery()) { return r.next() ? Optional.of(record(r)) : Optional.empty(); }
        } catch (SQLException e) { throw new IllegalStateException("Cannot get secret", e); }
    }

    @Override
    public void write(String tenantId, Secret secret) {
        try (Connection c = open(path, false); PreparedStatement s = c.prepareStatement(INSERT +
                " ON CONFLICT(tenant_id,name) DO UPDATE SET value=excluded.value, private_key_hash=excluded.private_key_hash")) {
            bind(s, tenantId, secret); s.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Cannot write secret", e); }
    }

    @Override
    public boolean delete(String tenantId, Secret expected) {
        try (Connection c = open(path, false); PreparedStatement s = c.prepareStatement(
                "DELETE FROM secrets WHERE tenant_id=? AND name=? AND value=? AND private_key_hash=?")) {
            bind(s, tenantId, expected);
            return s.executeUpdate() == 1;
        } catch (SQLException e) { throw new IllegalStateException("Cannot delete secret", e); }
    }

    private static void bind(PreparedStatement s, String tenant, Secret secret) throws SQLException {
        s.setString(1, tenant); s.setString(2, secret.getName());
        s.setString(3, secret.getValue()); s.setString(4, secret.getPrivate_key_hash());
    }

    private static Secret record(ResultSet r) throws SQLException {
        return Secret.builder().name(r.getString(1)).value(r.getString(2)).private_key_hash(r.getString(3)).build();
    }

    /** Import all legacy tenant files in one transaction; collisions or malformed input roll back everything. */
    public int importDirectory(Path source, ObjectMapper mapper) throws IOException, SQLException {
        if (!Files.isDirectory(source)) throw new IOException("Legacy data directory does not exist");
        try (var files = Files.list(source); Connection c = open(path, false)) {
            c.setAutoCommit(false);
            int count = 0;
            try (PreparedStatement s = c.prepareStatement(INSERT)) {
                for (Path file : files.sorted().toList()) {
                    String tenant = file.getFileName().toString();
                    if (!tenant.matches("[a-zA-Z0-9_-]+") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Unexpected entry in legacy data directory: " + tenant);
                    try (var reader = Files.newBufferedReader(file)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Secret secret = mapper.readValue(line, Secret.class);
                            if (secret == null || secret.getName() == null || secret.getValue() == null || secret.getPrivate_key_hash() == null)
                                throw new IOException("Incomplete record in tenant " + tenant);
                            bind(s, tenant, secret); s.executeUpdate(); count++;
                        }
                    }
                }
                c.commit();
                return count;
            } catch (IOException | SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }

    /** Consistent snapshot of a live database; only publishes the file after VACUUM succeeds. */
    public static void backup(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("SQLite backup source is missing");
        Path destination = target.toAbsolutePath().normalize();
        if (source.toAbsolutePath().normalize().equals(destination)) throw new IOException("Backup cannot replace the source database");
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "sqlite-backup-", ".tmp");
        try {
            try (Connection c = open(source, true); PreparedStatement s = c.prepareStatement("VACUUM INTO ?")) {
                s.setString(1, temporary.toString()); s.execute();
            } catch (SQLException e) { throw new IOException("Cannot snapshot SQLite database", e); }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
