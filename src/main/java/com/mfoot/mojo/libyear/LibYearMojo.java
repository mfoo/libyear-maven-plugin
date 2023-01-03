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
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Collections.emptySet;
import static org.codehaus.mojo.versions.filtering.DependencyFilter.filterDependencies;
import static org.codehaus.mojo.versions.utils.MavenProjectUtils.extractDependenciesFromDependencyManagement;

// TODO: Investigate setting "aggregator = true"
@Mojo(name = "libyear-report", threadSafe = true, defaultPhase = LifecyclePhase.VERIFY)
public class LibYearMojo extends AbstractMojo {
	private static final int INFO_PAD_SIZE = 72;


	// Map of groupId:artifactId -> Map of version number to release date
	static final Map<String, Map<String, LocalDate>> dependencyVersionReleaseDates = Maps.newHashMap();

	/**
	 * Wait until reaching the last project before executing sonar when attached to phase
	 */
	// TODO: This is designed to handle parallel plugin execution, but this is untested, especially with the cache
	static final AtomicInteger readyProjectsCounter = new AtomicInteger();
	static final AtomicLong libWeeksOutDated = new AtomicLong();
	protected static long TIMEOUT_SECONDS = 10;
	protected static String SEARCH_URI = "https://search.maven.org";

	protected final RepositorySystem repositorySystem;

	protected final org.eclipse.aether.RepositorySystem aetherRepositorySystem;

	@Parameter(defaultValue = "${project}", required = true, readonly = true)
	protected MavenProject project;

	@Parameter(defaultValue = "${settings}", readonly = true)
	protected Settings settings;

	@Parameter(property = "maven.version.ignore")
	protected Set<String> ignoredVersions;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	protected MavenSession session;

	/**
	 * Whether to allow snapshots when searching for the latest version of an artifact.
	 *
	 * @since 1.0-alpha-1rulesUrirulesUri
	 */
	@Parameter(property = "allowSnapshots", defaultValue = "false")
	protected boolean allowSnapshots;

	@Parameter(property = "pluginManagementDependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
	protected List<String> pluginManagementDependencyIncludes;

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
	 * @since 2.12.0
	 */
	@Parameter(property = "pluginManagementDependencyExcludes")
	protected List<String> pluginManagementDependencyExcludes;

	@Parameter(property = "processDependencyManagementTransitive", defaultValue = "false")
	private boolean processDependencyManagementTransitive;

	@Parameter(property = "processDependencyManagement", defaultValue = "true")
	private boolean processDependencyManagement;

	@Parameter(property = "processDependencies", defaultValue = "true")
	protected boolean processDependencies;

	@Parameter(property = "processPluginDependenciesInPluginManagement", defaultValue = "true")
	private boolean processPluginDependenciesInPluginManagement;

	@Parameter(property = "processPluginDependencies", defaultValue = "true")
	protected boolean processPluginDependencies;

	private VersionsHelper helper;

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
	 * @since 2.12.0
	 */
	@Parameter(property = "pluginDependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
	protected List<String> pluginDependencyIncludes;

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
	 * @since 2.12.0
	 */
	@Parameter(property = "pluginDependencyExcludes")
	protected List<String> pluginDependencyExcludes;

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
	 * @since 2.12.0
	 */
	@Parameter(property = "dependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
	protected List<String> dependencyIncludes;

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
	 * @since 2.12.0
	 */
	@Parameter(property = "dependencyExcludes")
	protected List<String> dependencyExcludes;

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
	 * @since 2.12.0
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
	 * @since 2.12.0
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
	 * @since 1.0-alpha-1
	 */
	public void setProject(MavenProject project) {
		this.project = project;
	}

	public boolean isProcessingDependencyManagement() {
		return processDependencyManagement;
	}

	public boolean isProcessingDependencies() {
		return processDependencies;
	}

	public boolean isProcessingPluginDependencies() {
		return processPluginDependencies;
	}

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

	public VersionsHelper getHelper() throws MojoExecutionException {
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

	private void logUpdates(Map<Dependency, ArtifactVersions> updates, String section)
			throws IOException, InterruptedException {

		Map<String, Pair<LocalDate, LocalDate>> dependencyVersionUpdates = Maps.newHashMap();

		for (ArtifactVersions versions : updates.values()) {
			final String current;
			ArtifactVersion latest;
			if (versions.isCurrentVersionDefined()) {
				current = versions.getCurrentVersion().toString();
				latest = versions.getNewestUpdate(Optional.empty(), allowSnapshots);
			} else {
				ArtifactVersion newestVersion =
						versions.getNewestVersion(versions.getArtifact().getVersionRange(), allowSnapshots);
				current = versions.getArtifact().getVersionRange().toString();
				latest = newestVersion == null ? null
						: versions.getNewestUpdate(newestVersion, Optional.empty(), allowSnapshots);
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
		getLog().info("");
	}

	private float logDependencyUpdates(String section, Map<String, Pair<LocalDate, LocalDate>> dependencyVersionUpdates) {
		float[] yearsOutdated = {0};

		getLog().info("The following dependencies in " + section + " have newer versions:");

		// TODO: Sort alphabetically?
		dependencyVersionUpdates.forEach((dep, dates) -> {
			LocalDate currentReleaseDate = dates.getLeft();
			LocalDate latestReleaseDate = dates.getRight();

			if (currentReleaseDate.isAfter(latestReleaseDate)) {
				// This is a bug in the underlying logic, where the display-dependency-updates plugin will include
				// updates from e.g commons-io:commons-io 2.11.0 -> 20030203.000550, despite 2.11.0 being ~15 years
				// newer. We return here so we don't count a negative libyear count, even though the dependency may
				// still be outdated.
				return;
			}

			long libWeeksOutdated = ChronoUnit.WEEKS.between(currentReleaseDate, latestReleaseDate);
			float libYearsOutdated = libWeeksOutdated / 52f;

			String right = String.format(" %.2f libyears", libYearsOutdated);
			String left = "  " + dep + " ";

			// TODO Handle when the name is very long

			String versionWithDots = StringUtils.rightPad(left, INFO_PAD_SIZE - right.length(), ".");

			getLog().info(versionWithDots + right);
			yearsOutdated[0] += libYearsOutdated;
			libWeeksOutDated.getAndAdd(libWeeksOutdated);
		});

		getLog().info("");

		return yearsOutdated[0];
	}

	private Optional<LocalDate> getReleaseDate(String groupId, String artifactId, String version)
			throws IOException, InterruptedException {

		String ga = groupId + ":" + artifactId;
		Map<String, LocalDate> versionReleaseDates = dependencyVersionReleaseDates.getOrDefault(ga, Maps.newHashMap());
		if (versionReleaseDates.containsKey(version)) {
			return Optional.of(versionReleaseDates.get(version));
		}

		try {
			URI artifactUri = URI.create(String.format("%s/solrsearch/select?q=g:%s+AND+a:%s+AND+v:%s&wt=json", SEARCH_URI, groupId, artifactId, version));

			HttpRequest request = HttpRequest.newBuilder()
					.uri(artifactUri)
					.version(HttpClient.Version.HTTP_2)
					.timeout(Duration.of(TIMEOUT_SECONDS, SECONDS))
					.GET()
					.build();
			HttpClient client = HttpClient.newBuilder().build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				getLog().error(String.format("Failed to fetch release date for %s:%s %s", groupId, artifactId, version));
				getLog().error(response.body());
				return Optional.empty();
			}

			JSONObject json = new JSONObject(response.body());
			// TODO: Handle packages that aren't on Maven Central - currently they will fail
			// TODO: Support exclusions
			JSONObject queryResponse = json.getJSONObject("response");
			if (queryResponse.getLong("numFound") != 0) {
				Long epochTime = queryResponse.getJSONArray("docs").getJSONObject(0).getLong("timestamp");

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
			getLog().error(String.format("Failed to get release date for %s %s: %s", ga, version, e.getMessage()));
			return Optional.empty();
		}
	}

	private boolean isLastProjectInReactor() {
		return readyProjectsCounter.incrementAndGet() != session.getProjects().size();
	}
}
