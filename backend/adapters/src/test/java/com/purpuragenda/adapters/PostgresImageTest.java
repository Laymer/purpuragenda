package com.purpuragenda.adapters;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

class PostgresImageTest {
    @Test void exactImmutableImageRetainsTheSupportedPostgresRepository() {
        var image=DockerImageName.parse(MigrationTest.IMAGE);
        assertEquals("postgres",image.getRepository());
        assertTrue(image.isCompatibleWith(DockerImageName.parse("postgres")));
        assertEquals("sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc",image.getVersionPart());
    }
    @Test void combinedTagAndDigestReproducesThePinnedClientParserFailure() {
        var combined=DockerImageName.parse(MigrationTest.IMAGE.replace("postgres@","postgres:18.6-bookworm@"));
        assertFalse(combined.isCompatibleWith(DockerImageName.parse("postgres")));
    }
}
