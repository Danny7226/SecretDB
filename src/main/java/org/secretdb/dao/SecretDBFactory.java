package org.secretdb.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import org.secretdb.dao.impl.OnDiskSecretDB;
import org.secretdb.dao.impl.SQLiteSecretDB;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SecretDBFactory {
    private final Map<String, SecretDB> table;
    private final ObjectMapper om;
    private final SecretDB sqlite;
    public SecretDBFactory(final ObjectMapper om) {
        this.om = om;
        this.table = new ConcurrentHashMap<>();
        String storage = System.getProperty("secretdb.storage", "file");
        this.sqlite = switch (storage) {
            case "file" -> null;
            case "sqlite" -> new SQLiteSecretDB(SQLiteSecretDB.defaultPath());
            default -> throw new IllegalArgumentException("secretdb.storage must be file or sqlite");
        };
        if (this.sqlite == null) this.table.put(DB_MODE.READ.toString(), new OnDiskSecretDB(this.om));
    }

    /**
     * Returned Instance is based on write/read mode and tenantId
     * Each tenant will have their own write instance and all tenant will share the same read instance
     * This is based on the assumption that there won't be many tenants for our service to support
     * In case we have a lot of tenants to support, we could shard the write instances
     * @return a secret DB instance
     */
    public SecretDB getSecretDB(final String tenantId, @NonNull final DB_MODE mode) {
        if (this.sqlite != null) return this.sqlite;
        if (DB_MODE.READ.equals(mode)) return this.table.get(DB_MODE.READ.toString());

        final String key = tenantId + mode;

        return this.table.computeIfAbsent(key, ignored -> new OnDiskSecretDB(this.om));
    }

    public enum DB_MODE {
        READ,
        WRITE
    }
}

