/*
 * Copyright 2023 Martin Foot
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.mfoo.libyear;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.apache.maven.plugin.testing.ArtifactStubFactory.setVariableValueToObject;
import static org.codehaus.mojo.versions.utils.MockUtils.mockAetherRepositorySystem;
import static org.codehaus.mojo.versions.utils.MockUtils.mockRepositorySystem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.mojo.versions.filtering.WildcardMatcher;
import org.codehaus.mojo.versions.utils.DependencyBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Note: This class uses <code>Strictness.LENIENT</code> because some mocks are set up by the
 * versions-maven-plugin and these use an older version of Mockito.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@WireMockTest(httpPort = 8080)
public class LibYearMojoTest {
    // TODO: Tests with version numbers being referenced by variables
    // TODO: Test with version ranges

    /**
     * Test factory method. Generates a Plugin object representing the specified parameters.
     *
     * @param artifactId The Maven artifact ID
     * @param groupId The Maven group ID
     * @param version The Maven version
     * @return The Plugin object
     */
    private static Plugin pluginOf(String artifactId, String groupId, String version) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        plugin.setVersion(version);
        return plugin;
    }

    @AfterEach
    public void reset() {
        // TODO: Make these private and add a reset method in the mojo
        LibYearMojo.libWeeksOutDated.set(0);
        LibYearMojo.dependencyVersionReleaseDates.clear();
    }

    /**
     * This is a basic test to ensure that a project with a single dependency correctly shows
     * nothing when no updates are available.
     */
    @Test
    public void dependencyIsAlreadyOnTheLatestVersion() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();
        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog()).infoLogs.isEmpty());
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    /**
     * This is a basic test to ensure that a project with a single dependency correctly shows
     * available updates.
     */
    @Test
    public void dependencyUpdateAvailable() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer
        // Don't stub 1.1.0, there's no need to check its version
        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    /** This test checks that the output for dependencies with long names is formatted correctly. */
    @Test
    public void dependencyUpdateWithLongNameAvailable() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency-with-very-very-long-name", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency-with-very-very-long-name")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();
                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer
        stubResponseFor("default-group", "default-dependency-with-very-very-long-name", "1.0.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency-with-very-very-long-name", "2.0.0", now);

        mojo.execute();

        List<String> logs = ((InMemoryTestLogger) mojo.getLog()).infoLogs;
        Optional<String> logLine = logs.stream()
                .filter((l) -> l.contains("default-group:default-dependency-with-very-very-long-name"))
                .findFirst();
        assertTrue(logLine.isPresent());

        int dependencyFirstLineIndex = logs.indexOf(logLine.get());

        assertFalse(logs.get(dependencyFirstLineIndex).contains("."));
        assertTrue(logs.get(dependencyFirstLineIndex + 1).startsWith("  ...")
                && logs.get(dependencyFirstLineIndex + 1).endsWith(("1.00 libyears")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    @Test
    public void singleProjectWithDependencyUpdateAvailableDoesntShowEntireProjectLogLine() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream().noneMatch((l) -> l.contains("for the entire project")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    @Test
    public void dependencyUpdateAvailableButDependencyIsExcluded() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();
                        setProject(project);
                        allowProcessingAllDependencies(this);

                        setVariableValueToObject(
                                this, "dependencyExcludes", List.of("default-group:default-dependency"));

                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        // No need to stub output as no API calls should be made

        mojo.execute();

        assertFalse(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream().anyMatch((l) -> l.contains("default-group:default-dependency")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    @Test
    public void dependencyUpdateAvailableButVersionIsIgnored() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();
                        setProject(project);
                        allowProcessingAllDependencies(this);

                        setVariableValueToObject(this, "ignoredVersions", Set.of("2.0.0"));

                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        // No need to stub output as no API calls should be made

        mojo.execute();

        assertFalse(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream().anyMatch((l) -> l.contains("default-group:default-dependency")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    @Test
    public void dependencyUpdateAvailableButProcessingDependenciesIsDisabled() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();
                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setVariableValueToObject(this, "processDependencies", false);

                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer
        // Don't stub 1.1.0, there's no need to check its version
        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertFalse(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream().anyMatch((l) -> l.contains("default-group:default-dependency")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    /**
     * The plugin should cache the result of API calls to find the release date of every dependency
     * that it makes. This test ensures that despite running the plugin twice, we only make one API
     * call for each dependency version.
     */
    @Test
    public void cacheIsUsedForDependencyVersions() throws Exception {
        // Run one of the tests twice, which should only trigger one fetch per
        // dependency version
        dependencyUpdateAvailable();
        dependencyUpdateAvailable();

        // One call for v1.0.0
        verify(
                1,
                getRequestedFor(urlPathEqualTo("/solrsearch/select"))
                        .withQueryParam("q", equalTo("g:default-group AND a:default-dependency AND v:1.0.0")));

        // One call for v2.0.0
        verify(
                1,
                getRequestedFor(urlPathEqualTo("/solrsearch/select"))
                        .withQueryParam("q", equalTo("g:default-group AND a:default-dependency AND v:2.0.0")));
    }

    @Test
    public void dependencyUpdateAvailableWhenVersionSpecifiedInDependencyManagement() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0") // This is
                                        // overridden by
                                        // dependencyManagement
                                        .build()))
                                .withDependencyManagementDependencyList(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.1.0")
                                        .build()))
                                .build();
                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer than the version specified by
        // dependencyManagement
        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(2));
        stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));

        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    /**
     * This test has a project with a dependency with version 1.0.0. Dependency management pins it
     * at 1.1.0, and 2.0.0 is available. We exclude the plugin from considering dependency
     * management, meaning we should show the age between 1.0.0 and 2.0.0.
     */
    @Test
    public void
            dependencyUpdateAvailableWhenVersionSpecifiedInDependencyManagementButDependencyExcludedFromDependencyManagement()
                    throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0") // This is
                                        // overridden by
                                        // dependencyManagement
                                        .build()))
                                .withDependencyManagementDependencyList(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.1.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);

                        setVariableValueToObject(
                                this, "dependencyManagementExcludes", List.of("default-group:default-dependency"));

                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer than the version specified by
        // dependencyManagement
        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(2));
        stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default-dependency") && l.contains("2.00 libyears")));

        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    /**
     * This test has a project with a two-year-old dependency where the dependency version is
     * overridden by the dependencyManagement section to use a one-year-old version, but the plugin
     * setting enableDependencyManagement is set to false. We should ignore the one-year-old version
     * and suggest that the dependency is two years out-of-date.
     */
    @Test
    public void
            dependencyUpdateAvailableWhenVersionSpecifiedInDependencyManagementWhereProcessDependencyManagementIsDisabled()
                    throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0") // This is
                                        // overridden by
                                        // dependencyManagement
                                        .build()))
                                .withDependencyManagementDependencyList(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.1.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setVariableValueToObject(this, "processDependencyManagement", false);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer than the version specified by
        // dependencyManagement
        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(2));
        stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default-dependency") && l.contains("2.00 libyears")));

        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    /**
     * This test has a dependency with version 1.0.0, which is overridden by dependencyManagement to
     * 1.1.0. Version 2.0.0 is available.
     *
     * <p>We ensure that the proposed change is the libyears between 1.1.0 and 2.0.0.
     */
    @Test
    public void dependencyUpdateAvailableDependencyDefinedInDependencyManagement() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .withDependencyManagementDependencyList(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.1.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer. The current dependency version is 1.1.0,
        // overridden from
        // 1.0.0 by
        // dependencyManagement

        // TODO: This first stub probably shouldn't be needed as we shouldn't need to
        // fetch the release
        // year of this version
        // but without it, the test fails.
        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(10));
        stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    @Test
    public void pluginVersionUpdateAvailable() throws Exception {
        Plugin plugin = pluginOf("default-plugin", "default-group", "1.0.0");
        plugin.setDependencies(singletonList(dependencyFor("default-group", "default-dependency", "1.0.0")));

        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withPlugins(singletonList(plugin))
                                .build();

                        setProject(project);

                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer
        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    /**
     * This test has a plugin version 1.0.0 which has a dependency on another artifact version
     * 1.0.1. That version is overridden in dependency management to 1.1.0. Version 2.0.0 is
     * available.
     *
     * <p>This test ensures we suggest the libyears between 1.1.0 and 2.0.0.
     */
    @Test
    public void pluginVersionUpdateAvailableDependencyDefinedInDependencyManagement() throws Exception {
        Plugin plugin = pluginOf("default-plugin", "default-group", "1.0.0");
        plugin.setDependencies(singletonList(dependencyFor("default-group", "default-dependency", "1.0.1")));

        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withPlugins(singletonList(plugin))
                                .withDependencyManagementDependencyList(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.1.0")
                                        .build()))
                                .build();

                        setProject(project);

                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer
        stubResponseFor("default-group", "default-dependency", "1.0.1", now.minusYears(2));
        stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    /**
     * This test has a plugin version 1.0.0. That version is overridden in plugin management to
     * 1.1.0. Version 2.0.0 is available.
     *
     * <p>This test ensures we suggest the libyears between 1.1.0 and 2.0.0.
     */
    @Test
    public void pluginVersionUpdateAvailableDependencyDefinedInPluginManagement() throws Exception {
        Plugin plugin = pluginOf("default-plugin", "default-group", "1.0.0");
        plugin.setDependencies(singletonList(dependencyFor("default-group", "default-dependency", "1.0.1")));

        Plugin managedPlugin = pluginOf("default-plugin", "default-group", "1.0.0");
        plugin.setDependencies(singletonList(dependencyFor("default-group", "default-dependency", "1.1.0")));

        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withPlugins(singletonList(plugin))
                                .withPluginManagementPluginList(singletonList(managedPlugin))
                                .build();

                        setProject(project);

                        allowProcessingAllDependencies(this);

                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer
        stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    private Dependency dependencyFor(String groupID, String artifactId, String version) {
        Dependency dep = new Dependency();
        dep.setGroupId(groupID);
        dep.setArtifactId(artifactId);
        dep.setVersion(version);
        return dep;
    }

    /**
     * Stub the Maven search API response that looks for release information for a particular
     * version of a particular artifact
     */
    private void stubResponseFor(String groupId, String artifactId, String version, LocalDateTime time) {
        stubFor(get(urlPathEqualTo("/solrsearch/select"))
                .withQueryParam("q", equalTo(String.format("g:%s AND a:%s AND v:%s", groupId, artifactId, version)))
                .withQueryParam("wt", equalTo("json"))
                .willReturn(ok(getJSONResponseForVersion(groupId, artifactId, version, time))));
    }

    /**
     * Simulate the JSON response from the Maven Central repository search API. Docs available at <a
     * href="https://central.sonatype.org/search/rest-api-guide/">here</a>.
     */
    private String getJSONResponseForVersion(
            String groupId, String artifactId, String version, LocalDateTime releaseDate) {
        JSONObject root = new JSONObject();
        JSONObject response = new JSONObject();
        JSONArray versions = new JSONArray();
        JSONObject newVersion = new JSONObject();

        newVersion.put("id", String.format("%s:%s:%s", groupId, artifactId, version));
        newVersion.put("g", groupId);
        newVersion.put("a", artifactId);
        newVersion.put("v", version);
        newVersion.put("p", "jar");
        newVersion.put("timestamp", releaseDate.toEpochSecond(ZoneOffset.UTC) * 1000); // API response is in millis

        versions.put(newVersion);
        response.put("docs", versions);
        response.put("numFound", 1);
        root.put("response", response);

        return root.toString();
    }

    @Test
    public void apiCallToMavenFails() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        stubFor(get(urlPathEqualTo("/solrsearch/select")).willReturn(serverError()));

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .errorLogs.stream()
                        .anyMatch((l) -> l.contains(
                                "Failed to fetch release date for" + " default-group:default-dependency" + " 1.0.0")));
        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .errorLogs.stream()
                        .anyMatch((l) -> l.contains(
                                "Failed to fetch release date for" + " default-group:default-dependency" + " 2.0.0")));
    }

    @Test
    public void apiCallToMavenTimesOut() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");
                        setHttpTimeout(1);
                        setFetchRetryCount(0);

                        setLog(new InMemoryTestLogger());
                    }
                };

        stubFor(get(urlPathEqualTo("/solrsearch/select")).willReturn(ok().withFixedDelay(10_000 /* ms */)));

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .errorLogs.stream()
                        .anyMatch((l) -> l.contains("Failed to fetch release date for"
                                + " default-group:default-dependency"
                                + " 1.0.0 (request timed out)")));
        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .errorLogs.stream()
                        .anyMatch((l) -> l.contains("Failed to fetch release date for"
                                + " default-group:default-dependency"
                                + " 2.0.0 (request timed out)")));
        assertEquals(2, ((InMemoryTestLogger) mojo.getLog()).errorLogs.size());
    }

    @Test
    public void apiCallToMavenRetriesOnFailure() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setFetchRetryCount(2);

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // The first dependency should fail twice and return OK on the second retry
        stubFor(get(urlPathEqualTo("/solrsearch/select"))
                .withQueryParam(
                        "q",
                        equalTo(String.format(
                                "g:%s AND a:%s AND v:%s", "default-group", "default-dependency", "1.0.0")))
                .inScenario("Failure chain")
                .whenScenarioStateIs("Started")
                .willReturn(serverError())
                .willSetStateTo("First failure"));

        stubFor(get(urlPathEqualTo("/solrsearch/select"))
                .inScenario("Failure chain")
                .whenScenarioStateIs("First failure")
                .willReturn(serverError())
                .willSetStateTo("Second failure"));

        stubFor(get(urlPathEqualTo("/solrsearch/select"))
                .inScenario("Failure chain")
                .whenScenarioStateIs("Second failure")
                .willReturn(ok(
                        getJSONResponseForVersion("default-group", "default-dependency", "1.0.0", now.minusYears(1)))));

        // The second request will succeed first time
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
    }

    @Test
    public void dependencyUpdateAvailableButFetchingReleaseDateOfNewerVersionFails() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();

                        setProject(project);

                        allowProcessingAllDependencies(this);

                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
        stubFor(get(urlPathEqualTo("/solrsearch/select"))
                .withQueryParam(
                        "q",
                        equalTo(String.format(
                                "g:%s AND a:%s AND v:%s", "default-group", "default-dependency", "2.0.0")))
                .withQueryParam("wt", equalTo("json"))
                .willReturn(serverError()));

        mojo.execute();

        assertFalse(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream().anyMatch((l) -> l.contains("default-group:default-dependency")));
        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .errorLogs.contains(
                        "Failed to fetch release date for default-group:default-dependency" + " 2.0.0: Server Error"));
    }

    @Test
    public void dependencyUpdateAvailableButAPISearchDoesNotContainLatestVersion() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "1.1.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
        stubFor(get(urlPathEqualTo("/solrsearch/select"))
                .withQueryParam(
                        "q",
                        equalTo(String.format(
                                "g:%s AND a:%s AND v:%s", "default-group", "default-dependency", "2.0.0")))
                .withQueryParam("wt", equalTo("json"))
                .willReturn(ok("{\"response\":{\"docs\":[],\"numFound\":0}}")));

        mojo.execute();

        assertFalse(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream().anyMatch((l) -> l.contains("default-group:default-dependency")));
        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .debugLogs.contains("Could not find artifact for default-group:default-dependency" + " 2.0.0"));
    }

    @Test
    public void multipleDependenciesWithUpdatesAreSortedAlphabetically() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "2.0.0"});
                        put("second-dependency", new String[] {"5.0.0", "6.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(List.of(
                                        DependencyBuilder.newBuilder()
                                                .withGroupId("default-group")
                                                .withArtifactId("second-dependency")
                                                .withVersion("5.0.0")
                                                .build(),
                                        DependencyBuilder.newBuilder()
                                                .withGroupId("default-group")
                                                .withArtifactId("default-dependency")
                                                .withVersion("1.0.0")
                                                .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);
                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        // Mark version 2.0.0 as a year newer
        // Don't stub 1.1.0, there's no need to check its version
        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);
        stubResponseFor("default-group", "second-dependency", "5.0.0", now.minusYears(5));
        stubResponseFor("default-group", "second-dependency", "6.0.0", now.minusYears(3));

        mojo.execute();

        List<String> infoLogs = ((InMemoryTestLogger) mojo.getLog()).infoLogs;
        String firstLogInvolvingDefaultGroup = infoLogs.stream()
                .filter(l -> l.contains("default-group"))
                .findFirst()
                .get();
        int indexOfFirstLog = infoLogs.indexOf(firstLogInvolvingDefaultGroup);
        assertTrue(infoLogs.get(indexOfFirstLog + 1).contains("second-dependency"));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
    }

    @Test
    public void projectExceedsMaxLibYearsAndShouldFailTheBuild() throws Exception {
        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "2.0.0"});
                    }
                })) {
                    {
                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(singletonList(DependencyBuilder.newBuilder()
                                        .withGroupId("default-group")
                                        .withArtifactId("default-dependency")
                                        .withVersion("1.0.0")
                                        .build()))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);

                        setVariableValueToObject(this, "maxLibYears", 0.1f);

                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertEquals("Dependencies exceed maximum specified age in libyears", ex.getMessage());

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .errorLogs.stream()
                        .anyMatch(l ->
                                l.contains("This module exceeds the maximum" + " dependency age of 0.1 libyears")));
    }

    @Test
    public void reportFileGenerated(@TempDir Path tempDir) throws Exception {
        Path reportFile = tempDir.resolve("libyear_testreport.txt");

        LibYearMojo mojo =
                new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {
                    {
                        put("default-dependency", new String[] {"1.0.0", "2.0.0"});
                        put("default2-dependency", new String[] {"3.0.0", "4.0.0"});
                    }
                })) {
                    {
                        Dependency dep1 = DependencyBuilder.newBuilder()
                                .withGroupId("default-group")
                                .withArtifactId("default-dependency")
                                .withVersion("1.0.0")
                                .build();

                        Dependency dep2 = DependencyBuilder.newBuilder()
                                .withGroupId("default-group")
                                .withArtifactId("default2-dependency")
                                .withVersion("3.0.0")
                                .build();

                        MavenProject project = new MavenProjectBuilder()
                                .withDependencies(Arrays.asList(dep1, dep2))
                                .build();

                        setProject(project);
                        allowProcessingAllDependencies(this);

                        setVariableValueToObject(
                                this, "reportFile", reportFile.toAbsolutePath().toString());
                        setVariableValueToObject(this, "minLibYearsForReport", 2);

                        setPluginContext(new HashMap<>());

                        setSession(mockMavenSession(project));
                        setSearchUri("http://localhost:8080");

                        setLog(new InMemoryTestLogger());
                    }
                };

        LocalDateTime now = LocalDateTime.now();

        stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
        stubResponseFor("default-group", "default-dependency", "2.0.0", now);
        stubResponseFor("default-group", "default2-dependency", "3.0.0", now.minusYears(3));
        stubResponseFor("default-group", "default2-dependency", "4.0.0", now);

        mojo.execute();

        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
        assertTrue(((InMemoryTestLogger) mojo.getLog())
                .infoLogs.stream()
                        .anyMatch(
                                (l) -> l.contains("default-group:default2-dependency") && l.contains("3.00 libyears")));
        assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());

        String content = Files.readString(reportFile);
        assertFalse(content.contains("default-group:default-dependency") && content.contains("1.00 libyears"));
        assertTrue(content.contains("default-group:default2-dependency") && content.contains("3.00 libyears"));
    }

    private void allowProcessingAllDependencies(LibYearMojo mojo) throws IllegalAccessException {
        setVariableValueToObject(mojo, "ignoredVersions", emptySet());
        setVariableValueToObject(mojo, "processDependencies", true);
        setVariableValueToObject(mojo, "processPluginDependencies", true);
        setVariableValueToObject(mojo, "pluginDependencyIncludes", singletonList(WildcardMatcher.WILDCARD));
        setVariableValueToObject(mojo, "pluginDependencyExcludes", emptyList());
        setVariableValueToObject(mojo, "processPluginDependenciesInPluginManagement", true);
        setVariableValueToObject(mojo, "pluginManagementDependencyIncludes", singletonList(WildcardMatcher.WILDCARD));
        setVariableValueToObject(mojo, "pluginManagementDependencyExcludes", emptyList());
        setVariableValueToObject(mojo, "processDependencyManagement", true);
        setVariableValueToObject(mojo, "dependencyManagementIncludes", singletonList(WildcardMatcher.WILDCARD));
        setVariableValueToObject(mojo, "dependencyManagementExcludes", emptyList());
        setVariableValueToObject(mojo, "dependencyIncludes", singletonList(WildcardMatcher.WILDCARD));
        setVariableValueToObject(mojo, "dependencyExcludes", emptyList());
    }

    private MavenSession mockMavenSession(MavenProject project) {
        MavenSession session = org.codehaus.mojo.versions.utils.MockUtils.mockMavenSession();
        Mockito.when(session.getProjectDependencyGraph()).thenReturn(new ProjectDependencyGraph() {
            @Override
            public List<MavenProject> getAllProjects() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<MavenProject> getSortedProjects() {
                return List.of(project);
            }

            @Override
            public List<MavenProject> getDownstreamProjects(MavenProject mavenProject, boolean b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<MavenProject> getUpstreamProjects(MavenProject mavenProject, boolean b) {
                throw new UnsupportedOperationException();
            }
        });
        return session;
    }
}
