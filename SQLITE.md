# SQLite storage and delete

The file backend remains available alongside the SQLite backend.

## Storage choice

The existing file implementation remains the default. To use SQLite:

```sh
mvn verify
java -Dsecretdb.storage=sqlite -jar target/SecretDB-1.0-SNAPSHOT.jar 8080
```

The database is `${user.home}/secret_db/sqlite/secrets.db`. No database server is needed. The factory shares one lightweight SQLite DAO; each operation opens and closes its own connection. SQLite serializes writes, with a 10-second busy timeout. No connection pool or tenant cache is added for SQLite.

The composite key `(tenant_id, name)` isolates tenants and makes name lookup indexed. An upsert updates just that record; it does not read and rewrite all records in Java. `list` still returns a complete list to satisfy the existing interface. The database uses SQLite's default rollback journal, not WAL.

Values remain the existing Base64 encrypted strings. **The SQLite file itself is not encrypted**: names, tenant IDs and existing key hashes remain readable. This change does not redesign the encryption or existing PUT authorization behavior.

## Inspect or manually delete records

With the optional `sqlite3` command-line client:

```sh
sqlite3 "$HOME/secret_db/sqlite/secrets.db"
```

```sql
.headers on
.mode column
SELECT tenant_id, name, value FROM secrets;

-- Example only: replace these values with the intended tenant/name.
DELETE FROM secrets WHERE tenant_id = 'mytenant' AND name = 'example';
```

SQLite manages the binary file. Do not edit it directly with a text editor. Direct SQL operates with your filesystem permissions and bypasses API key verification.

## Migrate existing files (offline)

Stop the server before importing so the file set does not change during migration. Keep your existing backup. Run:

```sh
java -cp target/SecretDB-1.0-SNAPSHOT.jar org.secretdb.dao.ImportLegacy \
  "$HOME/secret_db/data" "$HOME/secret_db/sqlite/secrets.db"
```

This imports the original `name`, ciphertext `value`, and `private_key_hash` without decrypting them. All inserts run in one transaction. Invalid files/records or any existing `(tenant_id, name)` collision roll back the entire import, rather than silently overwriting records. An empty schema may remain after a failed import. Source files are never changed or removed. The importer accepts only regular tenant files named with letters, digits, `_` or `-`.

Start with `-Dsecretdb.storage=sqlite` after a successful import. Switching back to `file` reads the old files, which do **not** receive subsequent SQLite writes or deletes. There is no automatic synchronization or reverse migration.

## Delete through the API / CLI

Re-source the updated `client` file after filling in your existing tenant/key configuration:

```sh
sdb d "example"
```

The new delete helper requires Python 3 for JSON encoding and curl supporting `--fail-with-body`. It sends the key in the request body via stdin, and correctly handles names/keys with spaces, quotes or backslashes. Existing read/write helpers are unchanged.

HTTP: `DELETE /api/{tenant}` with JSON `{"key":"...","name":"..."}`.

- `204`: deleted.
- `400`: missing/invalid fields or malformed request.
- `403`: key does not match the existing record's stored SHA-256 verifier.
- `404`: record not found.
- `409`: record changed/disappeared between validation and deletion; read again and retry.

The DAO deletes only if name, ciphertext and key hash still match the validated snapshot. Both file and SQLite implementations support this conditional delete. Legacy records without a usable key hash cannot be deleted through this API; repair them through your existing workflow or use direct local storage access. File operations are serialized within one JVM and use temporary-file replacement; do not run multiple file-backend servers or manually edit files while requests are executing.

## Backup and restore

Daily backup still copies legacy files. If a SQLite database exists, it also writes a consistent SQLite snapshot to:

```text
~/secret_db/backup/YYYY-MM-DD/sqlite/secrets.db
```

The existing external-backup destination also receives this snapshot when configured. The snapshot uses SQLite `VACUUM INTO` rather than copying a live database file, and publishes a completed temporary file. A failed backup does not mark the day successful. If SQLite is introduced after that day's file backup, the missing SQLite snapshot triggers another backup.

To restore, stop the server, preserve the current database separately, and replace it with a selected snapshot. Do not restore over a running SQLite database or leave journal files from a different database alongside the restored file. Move the entire current `sqlite` directory aside, create a new `sqlite` directory, and copy the chosen snapshot into it as `secrets.db`; this preserves the current database and its sidecars together. Restart with `-Dsecretdb.storage=sqlite`, then verify a known record through `sdb r`.

Before migration, stop the server and preserve the current legacy `data` directory. After import, compare record counts (`SELECT tenant_id, COUNT(*) FROM secrets GROUP BY tenant_id;`) with the legacy files and read known records using the usual keys. Keep the legacy files and pre-migration backup until validation is complete. If validation fails before any new SQLite writes, stop the server and restart without the SQLite flag to use the preserved file backend. After new SQLite writes, switching back alone loses visibility of those changes; do not treat the stale legacy files as an up-to-date rollback.

Reference: [SQLite VACUUM INTO documentation](https://www.sqlite.org/lang_vacuum.html#vacuum_with_an_into_clause).

## Validation

```sh
mvn test
# Unit/storage tests plus packaged-server HTTP end-to-end tests:
mvn verify
```

Tests use temporary databases, not your real secrets. Coverage includes tenant isolation, update/read/delete, stale-delete rejection, reopen persistence, concurrent writes, snapshot reopening, migration fidelity and rollback, and delete HTTP status/key validation.

`mvn verify` also runs three Failsafe end-to-end tests against the shaded JAR in child JVMs. They exercise legacy-to-SQLite migration through real API-created encrypted records, wrong-key deletion, deletion persistence after restart for both backends, tenant isolation, duplicate-import rollback, malformed DELETE requests, and snapshot restoration verified over HTTP. Tests bind only loopback ports, use isolated working/home directories and terminate their child processes. A local firewall/sandbox must permit localhost listeners. `mvn test` alone does not run these packaged-JAR tests.
