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

package com.mfoot.mojo.libyear;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.codehaus.mojo.versions.filtering.WildcardMatcher;
import org.codehaus.mojo.versions.utils.DependencyBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.apache.maven.plugin.testing.ArtifactStubFactory.setVariableValueToObject;
import static org.codehaus.mojo.versions.utils.MockUtils.mockAetherRepositorySystem;
import static org.codehaus.mojo.versions.utils.MockUtils.mockMavenSession;
import static org.codehaus.mojo.versions.utils.MockUtils.mockRepositorySystem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	// TODO: Cleanup
	// TODO: Run code formatter via plugin and enforce format
	// TODO: This file is using spaces, not tabs

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
		LibYearMojo.readyProjectsCounter.set(0);
	}

	/**
	 * This is a basic test to ensure that a project with a single dependency correctly shows available updates.
	 */
	@Test
	public void dependencyUpdateAvailable() throws Exception {
		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "1.1.0", "2.0.0"});
		}})

		) {{
			setProject(new MavenProjectBuilder().withDependencies(singletonList(DependencyBuilder.newBuilder().withGroupId("default-group").withArtifactId("default-dependency").withVersion("1.0.0").build())).build());
			allowProcessingAllDependencies(this);
			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		LocalDateTime now = LocalDateTime.now();

		// Mark version 2.0.0 as a year newer
		// Don't stub 1.1.0, there's no need to check its version
		stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
		stubResponseFor("default-group", "default-dependency", "2.0.0", now);

		mojo.execute();

		assertTrue(((InMemoryTestLogger) mojo.getLog()).infoLogs.stream().anyMatch((l) ->
				l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
	}

	/**
	 * This test checks that the output for dependencies with long names is formatted correctly.
	 */
	@Test
	public void dependencyUpdateWithLongNameAvailable() throws Exception {
		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency-with-very-very-long-name", new String[]{"1.0.0", "1.1.0", "2.0.0"});
		}})

		) {{
			setProject(new MavenProjectBuilder().withDependencies(singletonList(DependencyBuilder
					.newBuilder().withGroupId("default-group")
					.withArtifactId("default-dependency-with-very-very-long-name").withVersion("1.0.0").build())).build());
			allowProcessingAllDependencies(this);
			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		LocalDateTime now = LocalDateTime.now();

		// Mark version 2.0.0 as a year newer
		stubResponseFor("default-group", "default-dependency-with-very-very-long-name", "1.0.0", now.minusYears(1));
		stubResponseFor("default-group", "default-dependency-with-very-very-long-name", "2.0.0", now);

		mojo.execute();

		// TODO: Implement wrapping for libyear output for dependencies with long names then update this test
		assertTrue(((InMemoryTestLogger) mojo.getLog()).infoLogs.stream().anyMatch((l) ->
				l.contains("default-group:default-dependency-with-very-very-long-name") && l.contains("1.00 libyears")));
		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
	}

	/**
	 * The plugin should cache the result of API calls to find the release date of every dependency that it makes.
	 * This test ensures that despite running the plugin twice, we only make one API call for each dependency version.
	 */
	@Test
	public void cacheIsUsedForDependencyVersions() throws Exception {
		// Run one of the tests twice, which should only trigger one fetch per dependency version
		dependencyUpdateAvailable();
		dependencyUpdateAvailable();

		// One call for v1.0.0
		verify(1,
				getRequestedFor(urlPathEqualTo("/solrsearch/select"))
						.withQueryParam("q", equalTo("g:default-group AND a:default-dependency AND v:1.0.0")));

		// One call for v2.0.0
		verify(1, getRequestedFor(urlPathEqualTo("/solrsearch/select"))
				.withQueryParam("q", equalTo("g:default-group AND a:default-dependency AND v:2.0.0")));
	}

	@Test
	public void dependencyUpdateAvailableWhenVersionSpecifiedInDependencyManagement() throws Exception {
		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "1.1.0", "2.0.0"});
		}})

		) {{
			setProject(new MavenProjectBuilder()
					.withDependencies(singletonList(DependencyBuilder.newBuilder()
							.withGroupId("default-group")
							.withArtifactId("default-dependency")
							.withVersion("1.0.0") // This is overridden by dependencyManagement
					.build()))
					.withDependencyManagementDependencyList(
							singletonList(DependencyBuilder.newBuilder()
									.withGroupId("default-group")
									.withArtifactId("default-dependency")
									.withVersion("1.1.0").build()))
					.build());
			allowProcessingAllDependencies(this);
			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		LocalDateTime now = LocalDateTime.now();

		// Mark version 2.0.0 as a year newer than the version specified by dependencyManagement
		stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(2));
		stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
		stubResponseFor("default-group", "default-dependency", "2.0.0", now);

		mojo.execute();

		assertTrue(((InMemoryTestLogger) mojo.getLog()).infoLogs.stream().anyMatch((l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));

		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
	}

	/**
	 * This test has a project with a two-year-old dependency where the dependency version is overridden by the
	 * dependencyManagement section to use a one-year-old version, but the plugin setting enableDependencyManagement is
	 * set to false. We should ignore the one-year-old version and suggest that the dependency is two years out-of-date.
	 */
	@Test
	public void dependencyUpdateAvailableWhenVersionSpecifiedInDependencyManagementWhereProcessDependencyManagementIsDisabled() throws Exception {
		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "1.1.0", "2.0.0"});
		}})

		) {{
			setProject(new MavenProjectBuilder()
					.withDependencies(singletonList(DependencyBuilder.newBuilder()
							.withGroupId("default-group")
							.withArtifactId("default-dependency")
							.withVersion("1.0.0") // This is overridden by dependencyManagement
							.build()))
					.withDependencyManagementDependencyList(
							singletonList(DependencyBuilder.newBuilder()
									.withGroupId("default-group")
									.withArtifactId("default-dependency")
									.withVersion("1.1.0").build()))
					.build());
			allowProcessingAllDependencies(this);
			setVariableValueToObject(this, "processDependencyManagement", false);
			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		LocalDateTime now = LocalDateTime.now();

		// Mark version 2.0.0 as a year newer than the version specified by dependencyManagement
		stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(2));
		stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
		stubResponseFor("default-group", "default-dependency", "2.0.0", now);

		mojo.execute();

		assertTrue(((InMemoryTestLogger) mojo.getLog()).infoLogs.stream().anyMatch((l) -> l.contains("default-group:default-dependency") && l.contains("2.00 libyears")));

		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
	}

	/**
	 * This test has a dependency with version 1.0.0, which is overridden by
	 * dependencyManagement to 1.1.0. Version 2.0.0 is available.
	 * <p>
	 * We ensure that the proposed change is the libyears between 1.1.0 and
	 * 2.0.0.
	 */
	@Test
	public void dependencyUpdateAvailableDependencyDefinedInDependencyManagement() throws Exception {
		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "1.1.0", "2.0.0"});
		}})

		) {{
			setProject(new MavenProjectBuilder().withDependencies(
					singletonList(DependencyBuilder.newBuilder()
							.withGroupId("default-group")
							.withArtifactId("default-dependency")
							.withVersion("1.0.0")
							.build()))
					.withDependencyManagementDependencyList(singletonList(DependencyBuilder.newBuilder()
							.withGroupId("default-group")
							.withArtifactId("default-dependency")
							.withVersion("1.1.0")
							.build()))
					.build());
			allowProcessingAllDependencies(this);
			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		LocalDateTime now = LocalDateTime.now();

		// Mark version 2.0.0 as a year newer. The current dependency version is 1.1.0, overridden from 1.0.0 by
		// dependencyManagement

		// TODO: This first stub probably shouldn't be needed as we shouldn't need to fetch the release year of this version
		// but without it, the test fails.
		stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(10));
		stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
		stubResponseFor("default-group", "default-dependency", "2.0.0", now);

		mojo.execute();

		assertTrue(((InMemoryTestLogger) mojo.getLog()).infoLogs.stream().anyMatch(
				(l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
	}

	@Test
	public void pluginVersionUpdateAvailable() throws Exception {
		Plugin plugin = pluginOf("default-plugin", "default-group", "1.0.0");
		plugin.setDependencies(singletonList(dependencyFor("default-group", "default-dependency", "1.0.0")));

		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "1.1.0", "2.0.0"});
		}})

		) {{
			setProject(new MavenProjectBuilder().withPlugins(singletonList(plugin)).build());

			allowProcessingAllDependencies(this);
			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		LocalDateTime now = LocalDateTime.now();

		// Mark version 2.0.0 as a year newer
		stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
		stubResponseFor("default-group", "default-dependency", "2.0.0", now);

		mojo.execute();

		assertTrue(((InMemoryTestLogger) mojo.getLog()).infoLogs.stream().anyMatch((l) ->
				l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
	}

	/**
	 * This test has a plugin version 1.0.0 which has a dependency on another
	 * artifact version 1.0.1. That version is overridden in dependency management
	 * to 1.1.0. Version 2.0.0 is available.
	 * <p>
	 * This test ensures we suggest the libyears between 1.1.0 and 2.0.0.
	 */
	@Test
	public void pluginVersionUpdateAvailableDependencyDefinedInDependencyManagement() throws Exception {
		Plugin plugin = pluginOf("default-plugin", "default-group", "1.0.0");
		plugin.setDependencies(singletonList(dependencyFor("default-group", "default-dependency", "1.0.1")));

		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "1.1.0", "2.0.0"});
		}})

		) {{
			setProject(new MavenProjectBuilder().withPlugins(singletonList(plugin)).withDependencyManagementDependencyList(singletonList(DependencyBuilder.newBuilder().withGroupId("default-group").withArtifactId("default-dependency").withVersion("1.1.0").build())).build());

			allowProcessingAllDependencies(this);
			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		LocalDateTime now = LocalDateTime.now();

		// Mark version 2.0.0 as a year newer
		stubResponseFor("default-group", "default-dependency", "1.0.1", now.minusYears(2));
		stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
		stubResponseFor("default-group", "default-dependency", "2.0.0", now);

		mojo.execute();

		assertTrue(((InMemoryTestLogger) mojo.getLog()).infoLogs.stream().anyMatch((l) ->
				l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.isEmpty());
	}

	/**
	 * This test has a plugin version 1.0.0. That version is overridden in plugin management to 1.1.0.
	 * Version 2.0.0 is available.
	 * <p>
	 * This test ensures we suggest the libyears between 1.1.0 and 2.0.0.
	 */
	@Test
	public void pluginVersionUpdateAvailableDependencyDefinedInPluginManagement() throws Exception {
		Plugin plugin = pluginOf("default-plugin", "default-group", "1.0.0");
		plugin.setDependencies(singletonList(dependencyFor("default-group", "default-dependency", "1.0.1")));

		Plugin managedPlugin = pluginOf("default-plugin", "default-group", "1.0.0");
		plugin.setDependencies(singletonList(dependencyFor("default-group", "default-dependency", "1.1.0")));

		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "1.1.0", "2.0.0"});
		}})

		) {{
			setProject(new MavenProjectBuilder()
					.withPlugins(singletonList(plugin))
					.withPluginManagementPluginList(singletonList(managedPlugin)).build());

			allowProcessingAllDependencies(this);

			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		LocalDateTime now = LocalDateTime.now();

		// Mark version 2.0.0 as a year newer
		stubResponseFor("default-group", "default-dependency", "1.1.0", now.minusYears(1));
		stubResponseFor("default-group", "default-dependency", "2.0.0", now);

		mojo.execute();

		assertTrue(((InMemoryTestLogger) mojo.getLog()).infoLogs.stream().anyMatch((l) -> l.contains("default-group:default-dependency") && l.contains("1.00 libyears")));
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
	 * Stub the Maven search API response that looks for release information for a particular version of a particular
	 * artifact
	 */
	private void stubResponseFor(String groupId, String artifactId, String version, LocalDateTime time) {
		stubFor(get(urlPathEqualTo("/solrsearch/select")).withQueryParam("q", equalTo(String.format("g:%s AND a:%s AND v:%s", groupId, artifactId, version))).withQueryParam("wt", equalTo("json")).willReturn(ok(getJSONResponseForVersion(groupId, artifactId, version, time))));
	}

	/**
	 * Simulate the JSON response from the Maven Central repository search API. Docs available at
	 * <a href="https://central.sonatype.org/search/rest-api-guide/">here</a>.
	 */
	private String getJSONResponseForVersion(String groupId, String artifactId, String version, LocalDateTime releaseDate) {
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
		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "2.0.0"});
		}})) {{
			setProject(new MavenProjectBuilder().withDependencies(singletonList(DependencyBuilder.newBuilder()
					.withGroupId("default-group")
					.withArtifactId("default-dependency")
					.withVersion("1.0.0").build()))
				.build());
			allowProcessingAllDependencies(this);
			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		stubFor(get(urlPathEqualTo("/solrsearch/select")).willReturn(serverError()));

		mojo.execute();

		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.stream().anyMatch((l) ->
				l.contains("Failed to fetch release date for default-group:default-dependency 1.0.0")));
		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.stream().anyMatch((l) ->
				l.contains("Failed to fetch release date for default-group:default-dependency 2.0.0")));
		assertEquals(4, ((InMemoryTestLogger) mojo.getLog()).errorLogs.size());
	}

	@Test
	public void apiCallToMavenTimesOut() throws Exception {
		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "2.0.0"});
		}})) {{
			setProject(new MavenProjectBuilder().withDependencies(singletonList(DependencyBuilder.newBuilder()
					.withGroupId("default-group")
					.withArtifactId("default-dependency")
					.withVersion("1.0.0").build())).build());
			allowProcessingAllDependencies(this);
			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");
			setHttpTimeout(1);

			setLog(new InMemoryTestLogger());
		}};

		stubFor(get(urlPathEqualTo("/solrsearch/select")).willReturn(ok().withFixedDelay(10_000 /* ms */)));

		mojo.execute();

		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.stream().anyMatch((l) ->
				l.contains("Failed to get release date for default-group:default-dependency 1.0.0: request timed out")));
		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.stream().anyMatch((l) ->
				l.contains("Failed to get release date for default-group:default-dependency 2.0.0: request timed out")));
		assertEquals(2, ((InMemoryTestLogger) mojo.getLog()).errorLogs.size());
	}

	@Test
	public void dependencyUpdateAvailableButFetchingReleaseDateOfNewerVersionFails() throws Exception {
		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "1.1.0", "2.0.0"});
		}})

		) {{
			setProject(new MavenProjectBuilder().withDependencies(singletonList(DependencyBuilder.newBuilder()
					.withGroupId("default-group")
					.withArtifactId("default-dependency")
					.withVersion("1.0.0").build())).build());

			allowProcessingAllDependencies(this);

			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		LocalDateTime now = LocalDateTime.now();

		stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
		stubFor(get(urlPathEqualTo("/solrsearch/select"))
				.withQueryParam("q", equalTo(String.format("g:%s AND a:%s AND v:%s", "default-group", "default-dependency", "2.0.0"))
				).withQueryParam("wt", equalTo("json")).willReturn(serverError()));

		mojo.execute();

		assertFalse(((InMemoryTestLogger) mojo.getLog()).infoLogs.stream().anyMatch((l) -> l.contains("default-group:default-dependency")));
		assertTrue(((InMemoryTestLogger) mojo.getLog()).errorLogs.contains("Failed to fetch release date for default-group:default-dependency 2.0.0"));
	}

	@Test
	public void dependencyUpdateAvailableButAPISearchDoesNotContainLatestVersion() throws Exception {
		LibYearMojo mojo = new LibYearMojo(mockRepositorySystem(), mockAetherRepositorySystem(new HashMap<>() {{
			put("default-dependency", new String[]{"1.0.0", "1.1.0", "2.0.0"});
		}})

		) {{
			setProject(new MavenProjectBuilder().withDependencies(singletonList(DependencyBuilder.newBuilder()
					.withGroupId("default-group")
					.withArtifactId("default-dependency")
					.withVersion("1.0.0").build())).build());
			allowProcessingAllDependencies(this);
			setPluginContext(new HashMap<>());

			setSession(mockMavenSession());
			setSearchUri("http://localhost:8080");

			setLog(new InMemoryTestLogger());
		}};

		LocalDateTime now = LocalDateTime.now();

		stubResponseFor("default-group", "default-dependency", "1.0.0", now.minusYears(1));
		stubFor(get(urlPathEqualTo("/solrsearch/select")).withQueryParam("q", equalTo(String.format("g:%s AND a:%s AND v:%s", "default-group", "default-dependency", "2.0.0"))).withQueryParam("wt", equalTo("json")).willReturn(ok("{\"response\":{\"docs\":[],\"numFound\":0}}")));

		mojo.execute();

		assertFalse(((InMemoryTestLogger) mojo.getLog()).infoLogs.stream().anyMatch((l) -> l.contains("default-group:default-dependency")));
		assertTrue(((InMemoryTestLogger) mojo.getLog()).debugLogs.contains("Could not find artifact for default-group:default-dependency 2.0.0"));
	}

	private void allowProcessingAllDependencies(LibYearMojo mojo) throws IllegalAccessException {
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

	private void configurePluginSettings(LibYearMojo mojo, String settingName, Object value) throws IllegalAccessException {
		setVariableValueToObject(mojo, settingName, value);
	}
}
