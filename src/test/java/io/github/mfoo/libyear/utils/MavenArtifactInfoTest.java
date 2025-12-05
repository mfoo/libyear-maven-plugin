package io.github.mfoo.libyear.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Test class for MavenArtifactInfo.
 *
 * @author Marc Zottner
 */
class MavenArtifactInfoTest {

    /**
     * Test fetching the Last-Modified timestamp of a known Maven artifact.
     * @throws IOException
     */
    @Test
    void testGetLastModifiedTimestamp() throws IOException {
        // Given
        String groupId = "org.springframework";
        String artifactId = "spring-beans";
        String version = "7.0.1";

        // When
        long timestamp = MavenArtifactInfo.getLastModifiedTimestamp(groupId, artifactId, version);

        // Then
        // Expected: Thu, 20 Nov 2025 09:17:38 GMT = 1763630258000L
        assertEquals(1763630258000L, timestamp);
    }

    /**
     * Test fetching the Last-Modified timestamp of a known Maven artifact returns a positive value.
     * @throws IOException
     */
    @Test
    void testGetLastModifiedTimestampReturnsPositiveValue() throws IOException {
        // Given
        String groupId = "org.springframework";
        String artifactId = "spring-beans";
        String version = "7.0.1";

        // When
        long timestamp = MavenArtifactInfo.getLastModifiedTimestamp(groupId, artifactId, version);

        // Then
        assertTrue(timestamp > 0, "Timestamp should be positive");
        assertTrue(timestamp > 1700000000000L, "Timestamp should be after 2023");
    }

    /**
     * Test fetching the Last-Modified timestamp of a non-existing Maven artifact throws IOException.
     */
    @Test
    void testGetLastModifiedTimestampThrowsExceptionForInvalidArtifact() {
        // Given
        String groupId = "org.invalid";
        String artifactId = "invalid-artifact";
        String version = "99.99.99";

        // When & Then
        assertThrows(IOException.class, () -> {
            MavenArtifactInfo.getLastModifiedTimestamp(groupId, artifactId, version);
        });
    }
}
