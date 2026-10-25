package com.purpuragenda.adapters;

import com.purpuragenda.adapters.out.email.PostgresNotificationQueue;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationQueueStorageTest {
    @Test void unavailableStorageNeverExposesProviderDetailsAndRestoreNeverClaims() {
        var source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> { throw new SQLException("synthetic provider detail"); });
        var failure = assertThrows(IllegalStateException.class, () -> new PostgresNotificationQueue(source, false).claim());
        assertEquals("NOTIFICATION_STORAGE_FAILED", failure.getMessage());
        assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
        assertTrue(new PostgresNotificationQueue(source, true).claim().isEmpty());
        assertThrows(NullPointerException.class, () -> new PostgresNotificationQueue(null, false));
    }
}
