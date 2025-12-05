package io.github.mfoo.libyear.utils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Utility class to fetch Maven artifact information from Maven Central.
 *
 * @author Marc Zottner
 */
public final class MavenArtifactInfo {

    /** Maven Central base URL. */
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    /** DateTimeFormatter for parsing HTTP date strings. */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    /**
     * Private constructor to prevent instantiation.
     */
    private MavenArtifactInfo() {}

    /**
     * Parses an HTTP date string to a timestamp in milliseconds.
     *
     * @param httpDate http date string
     * @return timestamp in milliseconds
     */
    public static long parseHttpDate(String httpDate) {
        ZonedDateTime dateTime = ZonedDateTime.parse(httpDate.replace(" GMT", " +0000"), FORMATTER);
        return dateTime.toInstant().toEpochMilli();
    }

    /**
     * Fetches the Last-Modified timestamp of a Maven artifact from Maven Central.
     *
     * @param groupId groupId
     * @param artifactId artifactId
     * @param version version
     * @return last modified timestamp in milliseconds
     * @throws IOException if an I/O error occurs
     */
    public static long getLastModifiedTimestamp(String groupId, String artifactId, String version) throws IOException {
        final String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-"
                + version + ".pom";
        final HttpURLConnection connection = (HttpURLConnection) new URL(MAVEN_CENTRAL + path).openConnection();

        connection.setRequestMethod("HEAD");
        connection.connect();

        final int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("Artifact not found. HTTP response code: " + responseCode);
        }

        final String lastModified = connection.getHeaderField("Last-Modified");
        connection.disconnect();
        return parseHttpDate(lastModified);
    }
}
