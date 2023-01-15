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

import com.google.common.collect.Maps;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Settings;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.DefaultVersionsHelper;
import org.codehaus.mojo.versions.api.VersionsHelper;
import org.codehaus.mojo.versions.filtering.WildcardMatcher;
import org.codehaus.mojo.versions.utils.DependencyComparator;
import org.codehaus.mojo.versions.utils.MavenProjectUtils;
import org.codehaus.plexus.util.StringUtils;
import org.json.JSONObject;

import javax.inject.Inject;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Collections.emptySet;
import static org.codehaus.mojo.versions.filtering.DependencyFilter.filterDependencies;
import static org.codehaus.mojo.versions.utils.MavenProjectUtils.extractDependenciesFromDependencyManagement;

/**
 * Analyze dependencies and calculate how old they are.
 */
// TODO: Test whether or not we can set `threadSafe = true`
@Mojo(name = "libyear-report", defaultPhase = LifecyclePhase.VERIFY)
public class LibYearMojo extends AbstractMojo {
	/**
	 * Screen width for formatting the output number of libyears
	 */
	private static final int INFO_PAD_SIZE = 72;

	/**
	 * Cache to store the release dates of dependencies to reduce the number of API calls to {@link #SEARCH_URI}
	 */
	static final Map<String, Map<String, LocalDate>> dependencyVersionReleaseDates = Maps.newHashMap();

	/**
	 * Wait until reaching the last project before executing sonar when attached to phase
	 */
	// TODO: Investigate setting "aggregator = true" in the @Mojo class annotation - is this necessary?
	static final AtomicInteger readyProjectsCounter = new AtomicInteger(0);

	/**
	 * Track the running total of how many libweeks outdated we are. Used in multi-module builds.
	 */
	static final AtomicLong libWeeksOutDated = new AtomicLong();

	/**
	 * The Maven search URI quite often times out or returns HTTP 5xx. This variable controls how many
	 * times we can retry on failure before skipping this dependency.
	 */
	private static int MAVEN_API_HTTP_RETRY_COUNT = 5;

	/**
	 * HTTP timeout for making calls to {@link #SEARCH_URI}
	 */
	private static int MAVEN_API_HTTP_TIMEOUT_SECONDS = 2;

	/**
	 * API endpoint to query dependency release dates for age calculations.
	 *
	 */
	// TODO: Consider users requiring HTTP proxies
	private static String SEARCH_URI = "https://search.maven.org";

	private final RepositorySystem repositorySystem;
	private final org.eclipse.aether.RepositorySystem aetherRepositorySystem;
	private VersionsHelper helper;

	/**
	 * The Maven Project that the plugin is being executed on. Used for accessing e.g. the list of dependencies.
	 */
	@Parameter(defaultValue = "${project}", required = true, readonly = true)
	private MavenProject project;

	/**
	 * The Maven Settings that are being used, e.g. ~/.m2/settings.xml.
	 */
	@Parameter(defaultValue = "${settings}", readonly = true)
	private Settings settings;

	@Parameter(property = "maven.version.ignore", readonly = true)
	protected Set<String> ignoredVersions;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	private MavenSession session;

	/**
	 * Only take these artifacts into consideration.
	 * <p>
	 * Comma-separated list of extended GAV patterns.
	 *
	 * <p>
	 * Extended GAV: groupId:artifactId:version:type:classifier:scope
	 * </p>
	 * <p>
	 * The wildcard "*" can be used as the only, first, last or both characters in each token.
	 * The version token does support version ranges.
	 * </p>
	 *
	 * <p>
	 * Example: {@code "mygroup:artifact:*,*:*:*:*:*:compile"}
	 * </p>
	 *
	 * @since 1.0.0
	 */
	@Parameter(property = "pluginManagementDependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
	private List<String> pluginManagementDependencyIncludes;

	/**
	 * <p>Exclude these artifacts into consideration:<br/>
	 * Comma-separated list of {@code groupId:[artifactId[:version]]} patterns</p>
	 *
	 * <p>
	 * The wildcard "*" can be used as the only, first, last or both characters in each token.
	 * The version token does support version ranges.
	 * </p>
	 *
	 * <p>
	 * Example: {@code "mygroup:artifact:*,othergroup:*,anothergroup"}
	 * </p>
	 *
	 * @since 1.0.0
	 */
	@Parameter(property = "pluginManagementDependencyExcludes")
	private List<String> pluginManagementDependencyExcludes;

	// TODO: Add test coverage for this before exposing it as an option
	// @Parameter(property = "processDependencyManagementTransitive", defaultValue = "false")
	// private boolean processDependencyManagementTransitive;
	private final boolean processDependencyManagementTransitive = false;

	/**
	 * Whether to consider the dependencyManagement pom section. If this is set to false, dependencyManagement is
	 * ignored.
	 *
	 * @since 1.0.
	 */
	@Parameter(property = "processDependencyManagement", defaultValue = "true")
	private final boolean processDependencyManagement = true;

	/**
	 * Whether to consider the dependencies pom section. If this is set to false the plugin won't analyze dependencies,
	 * but it might analyze e.g. plugins depending on configuration.
	 */
	@Parameter(property = "processDependencies", defaultValue = "true")
	protected boolean processDependencies;

	// TODO: Add test coverage for this before exposing it as an option
	//	@Parameter(property = "processPluginDependenciesInPluginManagement", defaultValue = "true")
	//	private boolean processPluginDependenciesInPluginManagement;
	private final boolean processPluginDependenciesInPluginManagement = true;

	// TODO: Add test coverage for this before exposing it as an option
	//	@Parameter(property = "processPluginDependencies", defaultValue = "true")
	//	protected boolean processPluginDependencies;
	private final boolean processPluginDependencies = true;

	/**
	 * <p>Only take these artifacts into consideration:<br/>
	 * Comma-separated list of {@code groupId:[artifactId[:version]]} patterns</p>
	 *
	 * <p>
	 * The wildcard "*" can be used as the only, first, last or both characters in each token.
	 * The version token does support version ranges.
	 * </p>
	 *
	 * <p>
	 * Example: {@code "mygroup:artifact:*,othergroup:*,anothergroup"}
	 * </p>
	 *
	 * @since 1.0.0
	 */
	@Parameter(property = "pluginDependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
	private List<String> pluginDependencyIncludes;

	/**
	 * <p>Exclude these artifacts into consideration:<br/>
	 * Comma-separated list of {@code groupId:[artifactId[:version]]} patterns</p>
	 *
	 * <p>
	 * The wildcard "*" can be used as the only, first, last or both characters in each token.
	 * The version token does support version ranges.
	 * </p>
	 *
	 * <p>
	 * Example: {@code "mygroup:artifact:*,othergroup:*,anothergroup"}
	 * </p>
	 *
	 * @since 1.0.0
	 */
	@Parameter(property = "pluginDependencyExcludes")
	private List<String> pluginDependencyExcludes;

	/**
	 * Only take these artifacts into consideration.
	 * <p>
	 * Comma-separated list of extended GAV patterns.
	 *
	 * <p>
	 * Extended GAV: groupId:artifactId:version:type:classifier:scope
	 * </p>
	 * <p>
	 * The wildcard "*" can be used as the only, first, last or both characters in each token.
	 * The version token does support version ranges.
	 * </p>
	 *
	 * <p>
	 * Example: {@code "mygroup:artifact:*,*:*:*:*:*:compile"}
	 * </p>
	 *
	 * @since 1.0.0
	 */
	@Parameter(property = "dependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
	private List<String> dependencyIncludes;

	/**
	 * Exclude these artifacts from consideration.
	 * <p>
	 * Comma-separated list of extended GAV patterns.
	 *
	 * <p>
	 * Extended GAV: groupId:artifactId:version:type:classifier:scope
	 * </p>
	 * <p>
	 * The wildcard "*" can be used as the only, first, last or both characters in each token.
	 * The version token does support version ranges.
	 * </p>
	 *
	 * <p>
	 * Example: {@code "mygroup:artifact:*,*:*:*:*:*:provided,*:*:*:*:*:system"}
	 * </p>
	 *
	 * @since 1.0.0
	 */
	@Parameter(property = "dependencyExcludes")
	private List<String> dependencyExcludes;

	/**
	 * Only take these artifacts into consideration.
	 * <p>
	 * Comma-separated list of extended GAV patterns.
	 *
	 * <p>
	 * Extended GAV: groupId:artifactId:version:type:classifier:scope
	 * </p>
	 * <p>
	 * The wildcard "*" can be used as the only, first, last or both characters in each token.
	 * The version token does support version ranges.
	 * </p>
	 *
	 * <p>
	 * Example: {@code "mygroup:artifact:*,*:*:*:*:*:compile"}
	 * </p>
	 *
	 * @since 1.0.0
	 */
	@Parameter(property = "dependencyManagementIncludes", defaultValue = WildcardMatcher.WILDCARD)
	private List<String> dependencyManagementIncludes;

	/**
	 * Exclude these artifacts from consideration.
	 * <p>
	 * Comma-separated list of extended GAV patterns.
	 *
	 * <p>
	 * Extended GAV: groupId:artifactId:version:type:classifier:scope
	 * </p>
	 * <p>
	 * The wildcard "*" can be used as the only, first, last or both characters in each token.
	 * The version token does support version ranges.
	 * </p>
	 *
	 * <p>
	 * Example: {@code "mygroup:artifact:*,*:*:*:*:*:provided,*:*:*:*:*:system"}
	 * </p>
	 *
	 * @since 1.0.0
	 */
	@Parameter(property = "dependencyManagementExcludes")
	private List<String> dependencyManagementExcludes;

	@Inject
	public LibYearMojo(RepositorySystem repositorySystem,
					   org.eclipse.aether.RepositorySystem aetherRepositorySystem) {
		this.repositorySystem = repositorySystem;
		this.aetherRepositorySystem = aetherRepositorySystem;
	}

	/**
	 * Setter for property 'project'.
	 *
	 * @param project Value to set for property 'project'.
	 */
	protected void setProject(MavenProject project) {
		this.project = project;
	}

	/**
	 * Setter for property 'session'.
	 *
	 * @param session Value to set for property 'session'.
	 */
	protected void setSession(MavenSession session) {
		this.session = session;
	}

	/**
	 * Set the search URI
	 */
	protected void setSearchUri(String uri) {
		SEARCH_URI = uri;
	}

	/**
	 * Setter for the HTTP timeout for API calls
	 */
	protected void setHttpTimeout(int seconds) {
		MAVEN_API_HTTP_TIMEOUT_SECONDS = seconds;
	}

	/**
	 * Setter for the HTTP API fetch retry count
	 * @param count	the number of retries before giving up
	 */
	protected void setFetchRetryCount(int count) { MAVEN_API_HTTP_RETRY_COUNT = count; }

	/**
	 * Check if the mojo is configured to consider dependencyManagement
	 *
	 * @return	whether the mojo is configured to consider dependencyManagement
	 */
	public boolean isProcessingDependencyManagement() {
		return processDependencyManagement;
	}

	/**
	 * Check if the mojo is configured to consider dependencies
	 *
	 * @return	whether the mojo is configured to consider dependencies
	 */
	public boolean isProcessingDependencies() {
		return processDependencies;
	}

	/**
	 * Check if the mojo is configured to consider dependencies of plugins
	 *
	 * @return	whether the mojo is configured to consider plugin dependencies
	 */
	public boolean isProcessingPluginDependencies() {
		return processPluginDependencies;
	}

	/**
	 * Check if the mojo is configured to consider dependencies of plugins that are overridden in dependencyManagement
	 *
	 * @return	whether the mojo is configured to consider plugin dependencies that are overridden in dependencyManagement
	 */
	public boolean isProcessPluginDependenciesInDependencyManagement() {
		return processPluginDependenciesInPluginManagement;
	}

	// TODO: This is stolen from DisplayDependencyUpdatesMojo
	private static boolean dependenciesMatch(Dependency dependency, Dependency managedDependency) {
		if (!managedDependency.getGroupId().equals(dependency.getGroupId())) {
			return false;
		}

		if (!managedDependency.getArtifactId().equals(dependency.getArtifactId())) {
			return false;
		}

		if (managedDependency.getScope() == null
				|| Objects.equals(managedDependency.getScope(), dependency.getScope())) {
			return false;
		}

		if (managedDependency.getClassifier() == null
				|| Objects.equals(managedDependency.getClassifier(), dependency.getClassifier())) {
			return false;
		}

		return dependency.getVersion() == null
				|| managedDependency.getVersion() == null
				|| Objects.equals(managedDependency.getVersion(), dependency.getVersion());
	}

	/**
	 * Main entry point for the plugin.
	 *
	 * @throws MojoExecutionException	On failure, such as upstream HTTP issues
	 */
	public void execute() throws MojoExecutionException {
		Set<Dependency> dependencyManagement = emptySet();

		try {
			if (isProcessingDependencyManagement()) {
				dependencyManagement = filterDependencies(extractDependenciesFromDependencyManagement(project,
								processDependencyManagementTransitive, getLog()),
						dependencyManagementIncludes, dependencyManagementExcludes, "Dependency Management",
						getLog());

				logUpdates(getHelper().lookupDependenciesUpdates(dependencyManagement,
						false, false), "Dependency Management");
			}
			if (isProcessingDependencies()) {
				Set<Dependency> finalDependencyManagement = dependencyManagement;
				logUpdates(getHelper().lookupDependenciesUpdates(
								filterDependencies(project.getDependencies()
												.parallelStream()
												.filter(dep -> finalDependencyManagement.parallelStream()
														.noneMatch(depMan -> dependenciesMatch(dep, depMan)))
												.collect(() -> new TreeSet<>(DependencyComparator.INSTANCE), Set::add, Set::addAll),
										dependencyIncludes, dependencyExcludes, "Dependencies", getLog()),
								false, false),
						"Dependencies");
			}
			if (isProcessPluginDependenciesInDependencyManagement()) {
				logUpdates(getHelper().lookupDependenciesUpdates(filterDependencies(
								MavenProjectUtils.extractPluginDependenciesFromPluginsInPluginManagement(project),
								pluginManagementDependencyIncludes, pluginManagementDependencyExcludes,
								"Plugin Management Dependencies", getLog()), false, false),
						"pluginManagement of plugins");
			}
			if (isProcessingPluginDependencies()) {
				logUpdates(getHelper().lookupDependenciesUpdates(filterDependencies(
								MavenProjectUtils.extractDependenciesFromPlugins(project),
								pluginDependencyIncludes, pluginDependencyExcludes, "Plugin Dependencies",
								getLog()), false, false),
						"Plugin Dependencies");
			}

			if (isLastProjectInReactor() && readyProjectsCounter.get() != 1) {
				// If there's more than one project in the tree, show the summary
				getLog().info(String.format("Total years for entire project: %.2f", libWeeksOutDated.get() / 52f));
			}
		} catch (Exception e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	private VersionsHelper getHelper() throws MojoExecutionException {
		if (helper == null) {
			helper = new DefaultVersionsHelper.Builder()
					.withRepositorySystem(repositorySystem)
					.withAetherRepositorySystem(aetherRepositorySystem)
					.withIgnoredVersions(ignoredVersions)
					.withLog(getLog())
					.withMavenSession(session)
					.build();
		}
		return helper;
	}

	/**
	 *
	 * @param updates
	 * @param section
	 */
	private void logUpdates(Map<Dependency, ArtifactVersions> updates, String section) {
		Map<String, Pair<LocalDate, LocalDate>> dependencyVersionUpdates = Maps.newHashMap();

		for (ArtifactVersions versions : updates.values()) {
			final String current;
			ArtifactVersion latest;
			if (versions.isCurrentVersionDefined()) {
				current = versions.getCurrentVersion().toString();
				latest = versions.getNewestUpdate(Optional.empty(), false);
			} else {
				ArtifactVersion newestVersion =
						versions.getNewestVersion(versions.getArtifact().getVersionRange(), false);
				current = versions.getArtifact().getVersionRange().toString();
				latest = newestVersion == null ? null
						: versions.getNewestUpdate(newestVersion, Optional.empty(), false);
				if (latest != null
						&& ArtifactVersions.isVersionInRange(latest, versions.getArtifact().getVersionRange())) {
					latest = null;
				}
			}

			if (latest == null) {
				continue;
			}

			if (current.equals(latest.toString())) {
				continue;
			}

			// TODO: Maven mirrors?
			Artifact /* current */ artifact = versions.getArtifact();
			Optional<LocalDate> latestVersionReleaseDate = getReleaseDate(artifact.getGroupId(), artifact.getArtifactId(),
					latest.toString());
			Optional<LocalDate> currentVersionReleaseDate = getReleaseDate(artifact.getGroupId(), artifact.getArtifactId(),
					current);

			if (latestVersionReleaseDate.isEmpty() || currentVersionReleaseDate.isEmpty()) {
				// We couldn't find version details, skip
				continue;
			}

			dependencyVersionUpdates.put(artifact.getGroupId() + ":" + artifact.getArtifactId(), Pair.of(currentVersionReleaseDate.get(),
					latestVersionReleaseDate.get()));
		}

		float yearsOutdated = 0;
		if (!dependencyVersionUpdates.isEmpty()) {
			yearsOutdated = logDependencyUpdates(section, dependencyVersionUpdates);
		}

		// Total: Show this across the entire project, not just per section
		if (yearsOutdated != 0f) {
			getLog().info(String.format("Total years outdated: %.2f", yearsOutdated));
		}
	}

	/**
	 * Given a set of outdated dependencies, print how many libyears outdated they are to the screen.
	 *
	 * @param pomSection	The section of the pom we are analyzing
	 * @param outdatedDependencies	The outdated dependencies
	 * @return	A total libyear count for the provided dependencies
	 */
	private float logDependencyUpdates(String pomSection, Map<String, Pair<LocalDate, LocalDate>> outdatedDependencies) {
		float[] yearsOutdated = {0};

		getLog().info("");
		getLog().info("The following dependencies in " + pomSection + " have newer versions:");
		outdatedDependencies
				.entrySet()
				.stream().sorted(Map.Entry.comparingByKey())
				.forEach((dep) -> {
			LocalDate currentReleaseDate = dep.getValue().getLeft();
			LocalDate latestReleaseDate = dep.getValue().getRight();

			if (currentReleaseDate.isAfter(latestReleaseDate)) {
				// This is a bug in the underlying logic, where the display-dependency-updates plugin will include
				// updates from e.g commons-io:commons-io 2.11.0 -> 20030203.000550, despite 2.11.0 being ~15 years
				// newer. We return here so we don't count a negative libyear count, even though the dependency may
				// still be outdated.

				// Anybody experiencing this could use the ignoredVersions setting instead
				return;
			}

			long libWeeksOutdated = ChronoUnit.WEEKS.between(currentReleaseDate, latestReleaseDate);
			float libYearsOutdated = libWeeksOutdated / 52f;

			String right = String.format(" %.2f libyears", libYearsOutdated);
			String left = "  " + dep.getKey() + " ";

			// TODO Handle when the name is very long

			String versionWithDots = StringUtils.rightPad(left, INFO_PAD_SIZE - right.length(), ".");

			getLog().info(versionWithDots + right);
			yearsOutdated[0] += libYearsOutdated;
			libWeeksOutDated.getAndAdd(libWeeksOutdated);
		});

		getLog().info("");

		return yearsOutdated[0];
	}

	/**
	 * Make an API call to {@link #SEARCH_URI} to fetch the release date of the specified artifact. Uses the cache in
	 * {@link #dependencyVersionReleaseDates} if possible.
	 *
	 * @param groupId	The required artifact's groupId
	 * @param artifactId	The required artifact's artifactId
	 * @param version	The required artifact's version
	 * @return	The creation date of the artifact
	 */
	private Optional<LocalDate> getReleaseDate(String groupId, String artifactId, String version) {
		String ga = groupId + ":" + artifactId;
		Map<String, LocalDate> versionReleaseDates = dependencyVersionReleaseDates.getOrDefault(ga, Maps.newHashMap());
		if (versionReleaseDates.containsKey(version)) {
			return Optional.of(versionReleaseDates.get(version));
		}

		try {
			Optional<String> response = fetchReleaseDate(groupId, artifactId, version);

			if (response.isEmpty()) {
				// TODO: Duplicate code
				return Optional.empty();
			}

			JSONObject json = new JSONObject(response.get());
			JSONObject queryResponse = json.getJSONObject("response");
			if (queryResponse.getLong("numFound") != 0) {
				long epochTime = queryResponse.getJSONArray("docs").getJSONObject(0).getLong("timestamp");

				getLog().debug("Found release time " + epochTime + " for " + groupId + ":" + artifactId + ":" + version);
				LocalDate releaseDate = Instant.ofEpochMilli(epochTime).atZone(ZoneId.systemDefault()).toLocalDate();

				versionReleaseDates.put(version, releaseDate);
				dependencyVersionReleaseDates.put(ga, versionReleaseDates);
				return Optional.of(releaseDate);
			} else {
				getLog().debug("Could not find artifact for " + groupId + ":" + artifactId + " " + version);
				return Optional.empty();
			}
		} catch (Exception e) {
			getLog().error(String.format("Failed to fetch release date for %s %s: %s", ga, version, e.getMessage()));
			return Optional.empty();
		}
	}

	/**
	 * Make the API call to fetch the release date
	 */
	private Optional<String> fetchReleaseDate(String groupId, String artifactId, String version) throws IOException {
		URI artifactUri = URI.create(String.format("%s/solrsearch/select?q=g:%s+AND+a:%s+AND+v:%s&wt=json", SEARCH_URI, groupId, artifactId, version));

		getLog().debug("Fetching " + artifactUri);

		RequestConfig config = RequestConfig.custom()
				.setConnectTimeout(MAVEN_API_HTTP_TIMEOUT_SECONDS * 1000)
				.setConnectionRequestTimeout(MAVEN_API_HTTP_TIMEOUT_SECONDS * 1000)
				.setSocketTimeout(MAVEN_API_HTTP_TIMEOUT_SECONDS * 1000)
				.build();

		try (CloseableHttpClient httpClient = HttpClientBuilder.create()
				.setDefaultRequestConfig(config)
				.addInterceptorLast((HttpResponseInterceptor) (response, context) -> {
					// By default Apache HTTP client doesn't retry on 5xx errors
					if (response.getStatusLine().getStatusCode() >= 500) {
						throw new IOException(response.getStatusLine().getReasonPhrase());
					}
				})
				.setRetryHandler(new RetryAllExceptionsLoggingHandler(getLog(), MAVEN_API_HTTP_RETRY_COUNT, true))
				.build()) {
			final HttpGet httpGet = new HttpGet(artifactUri);

			try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
				if (response.getStatusLine().getStatusCode() != 200) {
					getLog().error(String.format("Failed to fetch release date for %s:%s %s (%s)", groupId, artifactId, version, response.getStatusLine().getReasonPhrase()));
					return Optional.empty();
				}

				String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
				return Optional.of(responseBody);
			} catch (ConnectTimeoutException | SocketTimeoutException e) {
				getLog().error(String.format("Failed to fetch release date for %s:%s %s (%s)", groupId, artifactId, version, "request timed out"));
				return Optional.empty();
			}
		}
	}

	/**
	 * Calculate if this is the last project in a multi-project pom. This is used to show a total "libyears outdated"
	 * figure for this project and all child projects.
	 *
	 * @return Whether this is the last project to be analysed by the plugin
	 */
	private boolean isLastProjectInReactor() {
		return readyProjectsCounter.incrementAndGet() != session.getProjects().size();
	}

	private static class RetryAllExceptionsLoggingHandler extends DefaultHttpRequestRetryHandler {
		Log logger;

		/**
		 * The superclass has a third constructor parameter listing Exception classes to not retry on, this
		 * class just passes an empty list. It also logs each retry.
		 *
		 * @param logger
		 * @param retryCount
		 * @param requestSentRetryEnabled
		 */
		public RetryAllExceptionsLoggingHandler(Log logger, int retryCount, boolean requestSentRetryEnabled) {
			super(retryCount, requestSentRetryEnabled, new ArrayList<>());
			this.logger = logger;
		}

		@Override
		public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
			logger.debug("Retrying count " + executionCount);
			return super.retryRequest(exception, executionCount, context);
		}
	}
}
