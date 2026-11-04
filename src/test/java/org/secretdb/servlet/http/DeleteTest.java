package org.secretdb.servlet.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.secretdb.cryptology.SHA256;
import org.secretdb.dao.*;
import org.secretdb.dao.impl.SQLiteSecretDB;
import org.secretdb.dao.model.Secret;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeleteTest {
    @TempDir Path dir;

    @Test void validatesRequestsAndRequiresCorrectKey() throws Exception {
        SQLiteSecretDB db = new SQLiteSecretDB(dir.resolve("secrets.db"));
        db.write("tenant", Secret.builder().name("a").value("cipher").private_key_hash(SHA256.hash("correct")).build());
        ReadWriteServlet servlet = new ReadWriteServlet();
        servlet.om = new ObjectMapper();
        servlet.secretDBFactory = new SecretDBFactory(servlet.om) {
            @Override public SecretDB getSecretDB(String tenant, DB_MODE mode) { return db; }
        };
        assertEquals(400, request(servlet, "not-json"));
        assertEquals(400, request(servlet, "{}"));
        assertEquals(403, request(servlet, "{\"name\":\"a\",\"key\":\"wrong\"}"));
        assertTrue(db.get("tenant", "a").isPresent());
        assertEquals(204, request(servlet, "{\"name\":\"a\",\"key\":\"correct\"}"));
        assertTrue(db.get("tenant", "a").isEmpty());
        assertEquals(404, request(servlet, "{\"name\":\"a\",\"key\":\"correct\"}"));
    }

    private int request(ReadWriteServlet servlet, String json) throws IOException {
        AtomicInteger status = new AtomicInteger(200);
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{HttpServletRequest.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPathInfo" -> "/tenant";
                case "getReader" -> new BufferedReader(new StringReader(json));
                default -> throw new UnsupportedOperationException(method.getName());
            });
        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{HttpServletResponse.class},
            (proxy, method, args) -> {
                if (method.getName().equals("sendError") || method.getName().equals("setStatus")) { status.set((Integer)args[0]); return null; }
                throw new UnsupportedOperationException(method.getName());
            });
        servlet.doDelete(req, resp);
        return status.get();
    }
}
