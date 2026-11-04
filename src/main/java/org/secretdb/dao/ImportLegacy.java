package org.secretdb.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.secretdb.dao.impl.SQLiteSecretDB;
import java.nio.file.Path;

/** Offline migration: stop the server before running. Never edits or removes the source files. */
public final class ImportLegacy {
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: ImportLegacy <legacy-data-directory> <sqlite-database-file>");
            System.exit(2);
        }
        try {
            int count = new SQLiteSecretDB(Path.of(args[1])).importDirectory(Path.of(args[0]), new ObjectMapper());
            System.out.println("Imported " + count + " encrypted records. Source files were not changed.");
        } catch (Exception e) {
            System.err.println("Import failed; no records from this import were committed. " + e.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
