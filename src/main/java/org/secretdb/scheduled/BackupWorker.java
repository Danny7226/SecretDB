package org.secretdb.scheduled;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class BackupWorker implements Runnable{
    private static final String PRIMARY_BACKUP_PATH = System.getProperty("user.home") + "/secret_db";
    private static final String SECONDARY_BACKUP_PATH = PRIMARY_BACKUP_PATH + "/external_backup";
    private static final String SOURCE_DATA_DIR =  PRIMARY_BACKUP_PATH + "/data";

    private static final Logger logger = LogManager.getLogger(BackupWorker.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Runs this operation.
     */
    @Override
    public void run() {
        try {
            for (String path: List.of(PRIMARY_BACKUP_PATH, SECONDARY_BACKUP_PATH)) {
                logger.info("Backing up for path: {}", path);

                /*
                 * if the symlink doesn't exist, shouldn't backup secondary
                 * note: when the actually inode doesn't exist, the symlink pointer would appear as non-exist
                 */
                if (SECONDARY_BACKUP_PATH.equals(path) && !new File(path).exists()) {
                    logger.warn("Secondary backup path is not implemented yet, skipping");
                    continue;
                }

                final String backupDir =  path + "/backup";
                final String backupLogLocation = backupDir + "/last_modify_date";

                final File backupLogFile = new File(backupLogLocation);

                Files.createDirectories(Paths.get(backupLogFile.getParentFile().getParent()));

                final LocalDate today = LocalDate.now();

                if (backupLogFile.exists() && 
                            today.equals(LocalDate.parse(readDateFromFile(backupLogFile), DATE_TIME_FORMATTER))) {
                    logger.info("Skipping iteration as I've backed up at this location [{}] today", path);
                    continue;
                }

                /*
                 * Finally we are here: if the logic passes all previous conditions, it means we should backup now
                 */
                logger.info("Back up journal exists: [{}], backing up and write journal", backupLogFile.exists());
                backupAndWriteToJournalFile(SOURCE_DATA_DIR, backupDir, backupLogFile, today);
            }
        } catch (final Throwable e) {
            logger.warn(
                    "Back-up worker got an exception somehow: {}", e);
        }
    }

    private static void copyFilesInFolder(final Path source, final Path target) throws IOException {
        // Ensure the target directory exists
        Files.createDirectories(target);

        // Walk through the source directory
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Copy each file to the target
                final Path targetFile = target.resolve(source.relativize(file));
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void backupAndWriteToJournalFile(
            final String sourceDir,
            final String backupDir,
            final File journalFile, 
            final LocalDate date) throws IOException {
        final String currentDate = date.format(DATE_TIME_FORMATTER);

        try {
            copyFilesInFolder(Paths.get(sourceDir), Paths.get(backupDir + "/" + currentDate));
            logger.info("Folder copied successfully!");
        } catch (IOException e) {
            logger.error("Failed to copy folder: " + e);
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(journalFile))) {
            writer.write(currentDate);
            writer.newLine();
            logger.info("Wrote new date to journal file");
        }
    }

    private static String readDateFromFile(final File file) throws IOException {
        try (final BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine().trim();
        }
    }
}