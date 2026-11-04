package org.secretdb.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import org.secretdb.dao.impl.OnDiskSecretDB;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SecretDBFactory {
    private final Map<String, SecretDB> table;
    private final ObjectMapper om;
    public SecretDBFactory(final ObjectMapper om) {
        this.om = om;
        this.table = new ConcurrentHashMap<>();
        this.table.put(DB_MODE.READ.toString(), new OnDiskSecretDB(this.om));
    }

    /**
     * Returned Instance is based on write/read mode and tenantId
     * Each tenant will have their own write instance and all tenant will share the same read instance
     * This is based on the assumption that there won't be many tenants for our service to support
     * In case we have a lot of tenants to support, we could shard the write instances
     * @return a secret DB instance
     */
    public SecretDB getSecretDB(final String tenantId, @NonNull final DB_MODE mode) {
        if (DB_MODE.READ.equals(mode)) return this.table.get(DB_MODE.READ.toString());

        final String key = tenantId + mode;

        return this.table.computeIfAbsent(key, ignored -> new OnDiskSecretDB(this.om));
    }

    public enum DB_MODE {
        READ,
        WRITE
    }
}
