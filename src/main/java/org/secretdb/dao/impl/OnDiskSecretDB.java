package org.secretdb.dao.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.secretdb.dao.SecretDB;
import org.secretdb.dao.model.Secret;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.nio.file.*;
import java.util.Objects;

/**
 * An implementation of SecretDB interface with on-disk IO
 */
public class OnDiskSecretDB implements SecretDB {
    private static final Logger logger = LogManager.getLogger(OnDiskSecretDB.class);
    private static final String HOME_DIRECTORY = System.getProperty("user.home");
    private final ObjectMapper om;
    private final Path dataDirectory;
    private static final Object FILE_LOCK = new Object();

    public OnDiskSecretDB(final ObjectMapper om) {
        this(om, Path.of(HOME_DIRECTORY, "secret_db", "data"));
    }

    public OnDiskSecretDB(final ObjectMapper om, Path dataDirectory) {
        this.om = om;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public List<Secret> list(final String tenantId) {
        synchronized (FILE_LOCK) {
            final String filePath = dataDirectory.resolve(tenantId).toString();
            final File dbFile = new File(filePath);

            if (!dbFile.exists()) return Collections.emptyList();

            return executeAndLogLatency(arg -> listSecrets(arg), dbFile, "List");
        }
    }

    public void getFromSpecifiedFile() {

    }

    @Override
    public Optional<Secret> get(final String tenantId, final String name) {
        synchronized (FILE_LOCK) {
            final String filePath = dataDirectory.resolve(tenantId).toString();
            final File dbFile = new File(filePath);

            if (!dbFile.exists()) return Optional.empty();

            return executeAndLogLatency(arg -> getSecret(arg, name), dbFile, "Get");
        }
    }

    @Override
    public void write(final String tenantId, final Secret secret) {
        synchronized (FILE_LOCK) {
            final String filePath = dataDirectory.resolve(tenantId).toString();
            final File dbFile = getOrCreate(filePath);

            executeAndLogLatency(arg -> updateSecrets(arg, secret), dbFile, "Write");
        }
    }

    private <T,R> R executeAndLogLatency(Function<T, R> func, T input, final String operationName) {
        final long startTime = System.currentTimeMillis();
        final R res = func.apply(input);
        final long endTime = System.currentTimeMillis();
        logger.info("{} DB operation succeeded in {} ms",  operationName, (endTime - startTime));
        return res;
    }

    private List<Secret> listSecrets(final File file) {
        try (BufferedReader br = new BufferedReader(new FileReader(file.getAbsolutePath()))) {
            List<Secret> secrets = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                final Secret secret = om.readValue(line, Secret.class);
                secrets.add(secret);
            }
            return secrets;
        } catch (final IOException e) {
            logger.error("Exception when reading file {}", e.toString());
            throw new RuntimeException("Exception when reading file", e);
        }
    }

    private Optional<Secret> getSecret(final File file, final String name) {
        Secret secret;
        try (BufferedReader br = new BufferedReader(new FileReader(file.getAbsolutePath()))) {
            String line;
            while ((line = br.readLine()) != null) {
                secret = om.readValue(line, Secret.class);
                if (secret.getName().equals(name)) {
                    return Optional.of(secret);
                }
            }

            // no-found
            return Optional.empty();
        } catch (final IOException e) {
            logger.error("Exception when reading file {}", e.toString());
            throw new RuntimeException("Exception when reading file", e);
        }
    }

    private Void updateSecrets(final File file, final Secret toUpdate) {

        final List<String> updatedLines = new ArrayList<>();
        boolean isExisting = false;

        Secret onFile;
        try (BufferedReader br = new BufferedReader(new FileReader(file.getAbsolutePath()))) {
            String line;
            while ((line = br.readLine()) != null) {
                onFile = om.readValue(line, Secret.class);
                if (onFile.getName().equals(toUpdate.getName())) {
                    isExisting = true;
                    onFile.setValue(toUpdate.getValue());
                    onFile.setPrivate_key_hash(toUpdate.getPrivate_key_hash());
                    updatedLines.add(om.writeValueAsString(onFile));
                } else {
                    updatedLines.add(line);
                }
            }

            if (!isExisting) { // if this is a new secret
                updatedLines.add(om.writeValueAsString(
                        Secret.builder()
                                .name(toUpdate.getName())
                                .value(toUpdate.getValue())
                                .private_key_hash(toUpdate.getPrivate_key_hash())
                                .build()));
            }
        } catch (final IOException e) {
            logger.error("Exception when reading file {}", e.toString());
            throw new RuntimeException("Exception when reading file", e);
        }

        replaceFile(file.toPath(), updatedLines);
        return null;
    }


    @Override
    public boolean delete(String tenantId, Secret expected) {
        synchronized (FILE_LOCK) {
            Path file = dataDirectory.resolve(tenantId);
            if (!Files.exists(file)) return false;
            try {
                List<String> kept = new ArrayList<>();
                boolean removed = false;
                for (String line : Files.readAllLines(file)) {
                    Secret current = om.readValue(line, Secret.class);
                    if (Objects.equals(current.getName(), expected.getName())) {
                        if (!Objects.equals(current.getValue(), expected.getValue())
                                || !Objects.equals(current.getPrivate_key_hash(), expected.getPrivate_key_hash())) return false;
                        removed = true;
                    } else kept.add(line);
                }
                if (removed) replaceFile(file, kept);
                return removed;
            } catch (IOException e) { throw new IllegalStateException("Cannot delete secret", e); }
        }
    }

    private static void replaceFile(Path file, List<String> lines) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "secret-write-", ".tmp");
            Files.write(temporary, lines);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Cannot replace secret file", e); }
        finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { logger.warn("Cannot remove temporary file", e); }
            }
        }
    }

    private File getOrCreate(final String filePath) {
        final File file = new File(filePath);

        try {
            // Ensure the parent directories exist
            final File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (parentDir.mkdirs()) {
                    logger.info("Parent directories created successfully. " + parentDir.getAbsolutePath());
                } else {
                    logger.info("Failed to create parent directories. " + parentDir.getAbsolutePath());
                }
            }

            // Create the file
            if (file.createNewFile()) {
                logger.info("File created successfully at: " + file.getAbsolutePath());
            } else {
                logger.info("File already exists at: " + file.getAbsolutePath());
            }

            return file;
        } catch (IOException e) {
            logger.error("Exception when creating new file {}", e.toString());
            throw new RuntimeException("Error creating db file", e);
        }
    }
}

