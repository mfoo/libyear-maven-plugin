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

import static java.util.Collections.emptySet;
import static org.codehaus.mojo.versions.filtering.DependencyFilter.filterDependencies;
import static org.codehaus.mojo.versions.utils.MavenProjectUtils.extractDependenciesFromDependencyManagement;
import static org.codehaus.mojo.versions.utils.MavenProjectUtils.extractDependenciesFromPlugins;
import static org.codehaus.mojo.versions.utils.MavenProjectUtils.extractPluginDependenciesFromPluginsInPluginManagement;

import com.google.common.collect.Maps;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.DefaultVersionsHelper;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.api.VersionsHelper;
import org.codehaus.mojo.versions.filtering.WildcardMatcher;
import org.codehaus.mojo.versions.rule.RuleService;
import org.codehaus.mojo.versions.rule.RulesServiceBuilder;
import org.codehaus.mojo.versions.utils.ArtifactFactory;
import org.codehaus.mojo.versions.utils.DependencyComparator;
import org.codehaus.mojo.versions.utils.VersionsExpressionEvaluator;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositorySystem;
import org.json.JSONObject;

/** Analyze dependencies and calculate how old they are. */
@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY)
public class LibYearMojo extends AbstractMojo {
    /** Screen width for formatting the output number of libyears */
    private static final int INFO_PAD_SIZE = 72;

    /**
     * Cache to store the release dates of dependencies to reduce the number of API calls to {@link
     * #searchUri}
     */
    private static final Map<String, Map<String, LocalDate>> dependencyVersionReleaseDates = Maps.newHashMap();

    /**
     * Track the running total of how many libweeks outdated we are. Used in multi-module builds.
     */
    private static final AtomicLong libWeeksOutDated = new AtomicLong();

    private final CloseableHttpClient httpClient;

    /**
     * Track the age of each module in a multi-module project.
     */
    private static final Map<String, Float> projectAges = new ConcurrentHashMap<>();

    /**
     * Track the age of the oldest version in use of each dependency
     */
    private static final Map<String, Float> dependencyAges = new ConcurrentHashMap<>();

    /**
     * The Maven search URI quite often times out or returns HTTP 5xx. This variable controls how
     * many times we can retry on failure before skipping this dependency.
     */
    private static int MAVEN_API_HTTP_RETRY_COUNT = 5;

    /** HTTP timeout for making calls to {@link #searchUri} */
    private static int MAVEN_API_HTTP_TIMEOUT_SECONDS = 5;

    /** API endpoint to query dependency release dates for age calculations. */
    @Parameter(property = "searchUri", defaultValue = "https://search.maven.org")
    private String searchUri;

    private final RepositorySystem repositorySystem;
    private final ArtifactFactory artifactFactory;

    private VersionsHelper helper;

    /**
     * The Maven Project that the plugin is being executed on. Used for accessing e.g. the list of
     * dependencies.
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /** The Maven Settings that are being used, e.g. ~/.m2/settings.xml. */
    @Parameter(defaultValue = "${settings}", readonly = true)
    private Settings settings;

    /**
     * Set of versions to ignore when checking for updates.
     */
    @Parameter(property = "maven.version.ignore", readonly = true)
    protected Set<String> ignoredVersions;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${mojoExecution}", required = true, readonly = true)
    private MojoExecution mojoExecution;

    /**
     * Whether to fail the build if the total number of libyears for the project exceeds this
     * target.
     *
     * <p>In a multi-module project, this value applies to individual modules, not the parent(s).
     *
     * <p>Note: If you are using this in a project's pom.xml then you may accidentally cause issues
     * when e.g. rebuilding older branches. Instead, it may make more sense to use this as a command
     * line plugin execution flag in CI or in a Maven profile used for building releases.
     *
     * @since 1.0.
     */
    @Parameter(property = "maxLibYears", defaultValue = "0.0")
    private float maxLibYears;

    /**
     * Path to the report file, if empty no report file will be generated.
     */
    @Parameter(property = "reportFile")
    private String reportFile;

    /**
     * Whether the dependency should be included in the report. If it is set to "0", all dependencies will be included,
     * otherwise only dependencies older than the specified number of years will be included.
     */
    @Parameter(property = "minLibYearsForReport", defaultValue = "0.0")
    private float minLibYearsForReport;

    /**
     * Only take these artifacts into consideration.
     *
     * <p>Comma-separated list of extended GAV patterns.
     *
     * <p>Extended GAV: groupId:artifactId:version:type:classifier:scope
     *
     * <p>The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     *
     * <p>Example: {@code "mygroup:artifact:*,*:*:*:*:*:compile"}
     *
     * @since 1.0.0
     */
    @Parameter(property = "pluginManagementDependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
    private List<String> pluginManagementDependencyIncludes;

    /**
     * Exclude these artifacts into consideration:<br>
     * Comma-separated list of {@code groupId:[artifactId[:version]]} patterns
     *
     * <p>The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     *
     * <p>Example: {@code "mygroup:artifact:*,othergroup:*,anothergroup"}
     *
     * @since 1.0.0
     */
    @Parameter(property = "pluginManagementDependencyExcludes")
    private List<String> pluginManagementDependencyExcludes;

    // TODO: Add test coverage for this before exposing it as an option
    // @Parameter(property = "processDependencyManagementTransitive", defaultValue =
    // "false")
    // private boolean processDependencyManagementTransitive;
    private final boolean processDependencyManagementTransitive = false;

    /**
     * Whether to consider the dependencyManagement pom section. If this is set to false,
     * dependencyManagement is ignored.
     *
     * @since 1.0.
     */
    @Parameter(property = "processDependencyManagement", defaultValue = "true")
    private boolean processDependencyManagement = true;

    /**
     * Whether to consider the dependencies pom section. If this is set to false the plugin won't
     * analyze dependencies, but it might analyze e.g. plugins depending on configuration.
     *
     * @since 1.0.
     */
    @Parameter(property = "processDependencies", defaultValue = "true")
    protected boolean processDependencies;

    // TODO: Add test coverage for this before exposing it as an option
    // @Parameter(property = "processPluginDependenciesInPluginManagement",
    // defaultValue = "true")
    // private boolean processPluginDependenciesInPluginManagement;
    private final boolean processPluginDependenciesInPluginManagement = true;

    // TODO: Add test coverage for this before exposing it as an option
    // @Parameter(property = "processPluginDependencies", defaultValue = "true")
    // protected boolean processPluginDependencies;
    private final boolean processPluginDependencies = true;

    /**
     * Only take these artifacts into consideration:<br>
     * Comma-separated list of {@code groupId:[artifactId[:version]]} patterns
     *
     * <p>The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     *
     * <p>Example: {@code "mygroup:artifact:*,othergroup:*,anothergroup"}
     *
     * @since 1.0.0
     */
    @Parameter(property = "pluginDependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
    private List<String> pluginDependencyIncludes;

    /**
     * Exclude these artifacts into consideration:<br>
     * Comma-separated list of {@code groupId:[artifactId[:version]]} patterns
     *
     * <p>The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     *
     * <p>Example: {@code "mygroup:artifact:*,othergroup:*,anothergroup"}
     *
     * @since 1.0.0
     */
    @Parameter(property = "pluginDependencyExcludes")
    private List<String> pluginDependencyExcludes;

    /**
     * Only take these artifacts into consideration.
     *
     * <p>Comma-separated list of extended GAV patterns.
     *
     * <p>Extended GAV: groupId:artifactId:version:type:classifier:scope
     *
     * <p>The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     *
     * <p>Example: {@code "mygroup:artifact:*,*:*:*:*:*:compile"}
     *
     * @since 1.0.0
     */
    @Parameter(property = "dependencyIncludes", defaultValue = WildcardMatcher.WILDCARD)
    private List<String> dependencyIncludes;

    /**
     * Exclude these artifacts from consideration.
     *
     * <p>Comma-separated list of extended GAV patterns.
     *
     * <p>Extended GAV: groupId:artifactId:version:type:classifier:scope
     *
     * <p>The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     *
     * <p>Example: {@code "mygroup:artifact:*,*:*:*:*:*:provided,*:*:*:*:*:system"}
     *
     * @since 1.0.0
     */
    @Parameter(property = "dependencyExcludes")
    private List<String> dependencyExcludes;

    /**
     * Only take these artifacts into consideration.
     *
     * <p>Comma-separated list of extended GAV patterns.
     *
     * <p>Extended GAV: groupId:artifactId:version:type:classifier:scope
     *
     * <p>The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     *
     * <p>Example: {@code "mygroup:artifact:*,*:*:*:*:*:compile"}
     *
     * @since 1.0.0
     */
    @Parameter(property = "dependencyManagementIncludes", defaultValue = WildcardMatcher.WILDCARD)
    private List<String> dependencyManagementIncludes;

    /**
     * Exclude these artifacts from consideration.
     *
     * <p>Comma-separated list of extended GAV patterns.
     *
     * <p>Extended GAV: groupId:artifactId:version:type:classifier:scope
     *
     * <p>The wildcard "*" can be used as the only, first, last or both characters in each token.
     * The version token does support version ranges.
     *
     * <p>Example: {@code "mygroup:artifact:*,*:*:*:*:*:provided,*:*:*:*:*:system"}
     *
     * @since 1.0.0
     */
    @Parameter(property = "dependencyManagementExcludes")
    private List<String> dependencyManagementExcludes;

    /**
     * Constructor for LibYearMojo.
     *
     * @param repositorySystem the Maven repository system
     * @param artifactFactory the artifact factory
     */
    @Inject
    public LibYearMojo(RepositorySystem repositorySystem, ArtifactFactory artifactFactory) {
        this.repositorySystem = repositorySystem;
        this.artifactFactory = artifactFactory;

        httpClient = setupHTTPClient();
    }

    private CloseableHttpClient setupHTTPClient() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(MAVEN_API_HTTP_TIMEOUT_SECONDS))
                .setConnectionRequestTimeout(Timeout.ofSeconds(MAVEN_API_HTTP_TIMEOUT_SECONDS))
                .setResponseTimeout(Timeout.ofSeconds(MAVEN_API_HTTP_TIMEOUT_SECONDS))
                .build();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(20);
        connectionManager.setDefaultMaxPerRoute(20);

        return HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(config)
                .addResponseInterceptorLast((HttpResponseInterceptor) (response, entity, context) -> {
                    // By default Apache HTTP client doesn't retry on 5xx errors
                    if (response.getCode() >= 500) {
                        throw new IOException(response.getReasonPhrase());
                    }
                })
                .setRetryStrategy(
                        new DefaultHttpRequestRetryStrategy(MAVEN_API_HTTP_RETRY_COUNT, TimeValue.ofSeconds(1)))
                .build();
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
     * Setter for property 'searchUri'.
     *
     * @param searchUri Value to set for property 'searchUri'.
     */
    protected void setSearchUri(String searchUri) {
        this.searchUri = searchUri;
    }

    /**
     * Setter for the HTTP timeout for API calls
     *
     * @param seconds the timeout in seconds
     */
    protected void setHttpTimeout(int seconds) {
        MAVEN_API_HTTP_TIMEOUT_SECONDS = seconds;
    }

    /**
     * Setter for the HTTP API fetch retry count
     *
     * @param count the number of retries before giving up
     */
    protected void setFetchRetryCount(int count) {
        MAVEN_API_HTTP_RETRY_COUNT = count;
    }

    /**
     * Reset static state. Package-private for testing purposes. This method clears all static
     * caches and counters used for tracking dependency ages and API responses across multi-module
     * builds.
     */
    static void resetStaticState() {
        libWeeksOutDated.set(0);
        dependencyVersionReleaseDates.clear();
        projectAges.clear();
        dependencyAges.clear();
    }

    private static boolean dependenciesMatch(Dependency dependency, Dependency managedDependency) {
        return managedDependency.getGroupId().equals(dependency.getGroupId())
                && managedDependency.getArtifactId().equals(dependency.getArtifactId());
    }

    /**
     * Main entry point for the plugin.
     *
     * @throws MojoExecutionException On failure, such as upstream HTTP issues
     */
    public void execute() throws MojoExecutionException {
        Set<Dependency> dependencyManagement = emptySet();

        float thisProjectLibYearsOutdated = 0;

        try {
            if (processDependencyManagement) {
                // Get all dependencies from <dependencyManagement />
                Set<Dependency> dependenciesFromDependencyManagement = extractDependenciesFromDependencyManagement(
                        project, processDependencyManagementTransitive, getLog());

                // Handle user settings - filter out anything that's excluded
                dependencyManagement = filterDependencies(
                        dependenciesFromDependencyManagement,
                        dependencyManagementIncludes,
                        dependencyManagementExcludes,
                        "Dependency Management",
                        getLog());

                generateReport(dependencyManagement, "Dependency Management");

                // Log anything that's left
                thisProjectLibYearsOutdated += processDependencyUpdates(
                        getHelper().lookupDependenciesUpdates(dependencyManagement.stream(), false, false),
                        "Dependency Management");
            }

            if (processDependencies) {
                Set<Dependency> finalDependencyManagement = dependencyManagement;

                // Get a list of dependencies and versions, using the versions from dependency management if they exist
                Set<Dependency> dependenciesExcludingOverridden = project.getDependencies().parallelStream()
                        .filter(dep -> finalDependencyManagement.parallelStream()
                                .noneMatch(depMan -> dependenciesMatch(dep, depMan)))
                        .collect(() -> new TreeSet<>(DependencyComparator.INSTANCE), Set::add, Set::addAll);

                // Handle user settings - filter out anything that's excluded
                Set<Dependency> dependencies = filterDependencies(
                        dependenciesExcludingOverridden,
                        dependencyIncludes,
                        dependencyExcludes,
                        "Dependencies",
                        getLog());

                generateReport(dependencies, "Dependency");

                // Log anything that's left
                thisProjectLibYearsOutdated += processDependencyUpdates(
                        getHelper().lookupDependenciesUpdates(dependencies.stream(), false, false), "Dependencies");
            }

            if (processPluginDependenciesInPluginManagement) {
                // Get all dependencies of plugins from dependencyManagement
                Set<Dependency> pluginDependenciesFromDepManagement =
                        extractPluginDependenciesFromPluginsInPluginManagement(project);

                // Handle user settings - filter out anything that's excluded
                Set<Dependency> filteredPluginDependenciesFromDepManagement = filterDependencies(
                        pluginDependenciesFromDepManagement,
                        pluginManagementDependencyIncludes,
                        pluginManagementDependencyExcludes,
                        "Plugin Management Dependencies",
                        getLog());

                generateReport(filteredPluginDependenciesFromDepManagement, "Plugin Management Dependency");

                // Log anything that's left
                thisProjectLibYearsOutdated += processDependencyUpdates(
                        getHelper()
                                .lookupDependenciesUpdates(
                                        filteredPluginDependenciesFromDepManagement.stream(), false, false),
                        "pluginManagement of plugins");
            }

            if (processPluginDependencies) {
                // Get all dependencies of plugins
                Set<Dependency> pluginDependencies = extractDependenciesFromPlugins(project);

                // Handle user settings - filter out anything that's excluded
                Set<Dependency> filteredPluginDependencies = filterDependencies(
                        pluginDependencies,
                        pluginDependencyIncludes,
                        pluginDependencyExcludes,
                        "Plugin Dependencies",
                        getLog());

                generateReport(filteredPluginDependencies, "Plugin Dependency");

                // Log anything that's left
                thisProjectLibYearsOutdated += processDependencyUpdates(
                        getHelper().lookupDependenciesUpdates(filteredPluginDependencies.stream(), false, false),
                        "Plugin Dependencies");
            }

            if (thisProjectLibYearsOutdated != 0) {
                getLog().info(String.format("This module is %.2f libyears behind", thisProjectLibYearsOutdated));
            }

            if (maxLibYears != 0 && thisProjectLibYearsOutdated >= maxLibYears) {
                getLog().info("");
                getLog().error("This module exceeds the maximum dependency age of " + maxLibYears + " libyears");
                throw new MojoExecutionException("Dependencies exceed maximum specified age in libyears");
            }

            projectAges.put(project.getName(), thisProjectLibYearsOutdated);
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void generateReport(Set<Dependency> dependencies, final String categorie) {
        if (StringUtils.isBlank(reportFile)) {
            return;
        }

        StringBuilder logsToReport = new StringBuilder();

        dependencies.stream().forEach(dependency -> {
            try {
                Artifact artifact = artifactFactory.createArtifact(dependency);

                Optional<LocalDate> currentReleaseDate =
                        getReleaseDate(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());

                String depName = dependency.getGroupId() + ":" + dependency.getArtifactId();
                String libYearsStr = "unknown";
                if (!currentReleaseDate.isEmpty()) {
                    long libWeeksOutdated = ChronoUnit.WEEKS.between(currentReleaseDate.get(), LocalDate.now());
                    float libYearsOutdated = libWeeksOutdated / 52f;

                    if (libYearsOutdated > 0
                            && (minLibYearsForReport <= 0 || libYearsOutdated > minLibYearsForReport)) {
                        libYearsStr = String.format(Locale.US, "%.2f", libYearsOutdated);
                    }
                }
                logsToReport
                        .append(depName)
                        .append(",")
                        .append(dependency.getVersion())
                        .append(",")
                        .append(dependency.getType())
                        .append(",")
                        .append(categorie)
                        .append(",")
                        .append(libYearsStr)
                        .append(System.lineSeparator());
            } catch (Exception e) {
                getLog().error("Exception by writing report", e);
            }
        });

        File targetDirectory = new File(project.getModel().getBuild().getDirectory());
        if (targetDirectory.exists() && targetDirectory.isDirectory()) {
            Path path = Paths.get(targetDirectory.getAbsolutePath(), reportFile);
            try {
                Files.write(
                        path, logsToReport.toString().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                getLog().error("Failed to write report file: " + reportFile, e);
            }
        }
    }

    private VersionsHelper getHelper() throws MojoExecutionException {
        if (helper == null) {
            RuleService ruleService = new RulesServiceBuilder()
                    .withMavenSession(session)
                    .withIgnoredVersions(ignoredVersions)
                    .withLog(getLog())
                    .build();
            PomHelper pomHelper =
                    new PomHelper(artifactFactory, new VersionsExpressionEvaluator(session, mojoExecution));
            helper = new DefaultVersionsHelper.Builder()
                    .withRepositorySystem(repositorySystem)
                    .withArtifactFactory(artifactFactory)
                    .withLog(getLog())
                    .withMavenSession(session)
                    .withPomHelper(pomHelper)
                    .withRuleService(ruleService)
                    .build();
        }
        return helper;
    }

    /**
     * Iterates over the list of updates for the current pom section, logging how far behind the latest version they are.
     *
     * @param updates   All available updates for this section
     * @param section   The name of the section (e.g. "Plugin Management")
     */
    private float processDependencyUpdates(Map<Dependency, ArtifactVersions> updates, String section) {
        Map<String, Pair<LocalDate, LocalDate>> dependencyVersionUpdates = Maps.newHashMap();

        for (ArtifactVersions versions : updates.values()) {
            if (versions.getCurrentVersion() == null) {
                continue;
            }

            final String current = versions.getCurrentVersion().toString();
            ArtifactVersion latest = versions.getNewestUpdateWithinSegment(Optional.empty(), false);

            if (latest == null) {
                continue;
            }

            if (current.equals(latest.toString())) {
                continue;
            }

            Artifact /* current */ artifact = versions.getArtifact();
            Optional<LocalDate> latestVersionReleaseDate =
                    getReleaseDate(artifact.getGroupId(), artifact.getArtifactId(), latest.toString());
            Optional<LocalDate> currentVersionReleaseDate =
                    getReleaseDate(artifact.getGroupId(), artifact.getArtifactId(), current);

            if (latestVersionReleaseDate.isEmpty() || currentVersionReleaseDate.isEmpty()) {
                // We couldn't find version details, skip
                continue;
            }

            String ga = String.format("%s:%s", artifact.getGroupId(), artifact.getArtifactId());
            dependencyVersionUpdates.put(ga, Pair.of(currentVersionReleaseDate.get(), latestVersionReleaseDate.get()));
        }

        if (dependencyVersionUpdates.isEmpty()) {
            return 0;
        }

        return logDependencyUpdates(section, dependencyVersionUpdates);
    }

    /**
     * Given a set of outdated dependencies, print how many libyears behind they are to the
     * screen.
     *
     * @param pomSection The section of the pom we are analyzing
     * @param outdatedDependencies The outdated dependencies
     * @return A total libyear count for the provided dependencies
     */
    private float logDependencyUpdates(
            String pomSection, Map<String, Pair<LocalDate, LocalDate>> outdatedDependencies) {
        if (outdatedDependencies.isEmpty()) {
            return 0.0f;
        }

        Map<String, Pair<LocalDate, LocalDate>> validOutdatedDependencies = outdatedDependencies.entrySet().stream()
                .filter((dep) -> {
                    LocalDate currentReleaseDate = dep.getValue().getLeft();
                    LocalDate latestReleaseDate = dep.getValue().getRight();

                    // This is a bug in the underlying logic, where the
                    // display-dependency-updates plugin will include
                    // updates from e.g. commons-io:commons-io 2.11.0 ->
                    // 20030203.000550, despite 2.11.0 being ~15 years
                    // newer. We return here, so we don't count a negative
                    // libyear count, even though the dependency may still be
                    // outdated. Anybody experiencing this could use the
                    // ignoredVersions setting instead
                    return !currentReleaseDate.isAfter(latestReleaseDate);
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (validOutdatedDependencies.isEmpty()) {
            return 0.0f;
        }

        float[] yearsOutdated = {0};

        getLog().info(String.format("The following dependencies in %s have newer versions:", pomSection));
        validOutdatedDependencies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach((dep) -> {
                    LocalDate currentReleaseDate = dep.getValue().getLeft();
                    LocalDate latestReleaseDate = dep.getValue().getRight();

                    long libWeeksOutdated = ChronoUnit.WEEKS.between(currentReleaseDate, latestReleaseDate);
                    float libYearsOutdated = libWeeksOutdated / 52f;

                    logDependencyAge(dep, libYearsOutdated);
                    yearsOutdated[0] += libYearsOutdated;
                    libWeeksOutDated.getAndAdd(libWeeksOutdated);

                    if (!dependencyAges.containsKey(dep.getKey())
                            || dependencyAges.get(dep.getKey()) < libYearsOutdated) {
                        dependencyAges.put(dep.getKey(), libYearsOutdated);
                    }
                });

        getLog().info("");

        return yearsOutdated[0];
    }

    /**
     * Display the output for how many libyears behind the specified dependency is. Wraps at {@link #INFO_PAD_SIZE}.
     * <p />
     * Prints output in the form
     * <p />
     * <code>
     *     mygroup:myartifact ................ 1.0 years
     *     mygroup:myartifactwithlonglonglongname
     *     ................................... 2.0 years
     * </code>
     *
     * @param dep   The dependency
     * @param libYearsOutdated  How many libyears behind it is
     */
    private void logDependencyAge(Map.Entry<String, Pair<LocalDate, LocalDate>> dep, float libYearsOutdated) {
        String right = String.format(Locale.US, " %.2f libyears", libYearsOutdated);
        String left = "  " + dep.getKey() + " ";

        if ((left.length() + right.length()) > INFO_PAD_SIZE) {
            getLog().info(left);
            String versionWithDots = StringUtils.rightPad("  ", INFO_PAD_SIZE - right.length(), ".");
            getLog().info(versionWithDots + right);
        } else {
            String versionWithDots = StringUtils.rightPad(left, INFO_PAD_SIZE - right.length(), ".");
            getLog().info(versionWithDots + right);
        }
    }

    /**
     * Make an API call to {@link #searchUri} to fetch the release date of the specified artifact.
     * Uses the cache in {@link #dependencyVersionReleaseDates} if possible.
     *
     * @param groupId The required artifact's groupId
     * @param artifactId The required artifact's artifactId
     * @param version The required artifact's version
     * @return The creation date of the artifact
     */
    private Optional<LocalDate> getReleaseDate(String groupId, String artifactId, String version) {
        String ga = String.format("%s:%s", groupId, artifactId);
        Map<String, LocalDate> versionReleaseDates = dependencyVersionReleaseDates.getOrDefault(ga, Maps.newHashMap());
        if (versionReleaseDates.containsKey(version)) {
            return Optional.of(versionReleaseDates.get(version));
        }

        try {
            Optional<String> response = fetchReleaseDate(groupId, artifactId, version);

            if (response.isEmpty()) {
                return Optional.empty();
            }

            JSONObject json = new JSONObject(response.get());
            JSONObject queryResponse = json.getJSONObject("response");
            if (queryResponse.getLong("numFound") != 0) {
                long epochTime =
                        queryResponse.getJSONArray("docs").getJSONObject(0).getLong("timestamp");

                getLog().debug(String.format("Found release time %d for %s:%s", epochTime, ga, version));

                LocalDate releaseDate = Instant.ofEpochMilli(epochTime)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate();

                versionReleaseDates.put(version, releaseDate);
                dependencyVersionReleaseDates.put(ga, versionReleaseDates);
                return Optional.of(releaseDate);
            } else {
                getLog().debug(String.format("Could not find artifact for %s %s", ga, version));
                return Optional.empty();
            }
        } catch (Exception e) {
            getLog().error(String.format("Failed to fetch release date for %s %s: %s", ga, version, e.getMessage()));
            return Optional.empty();
        }
    }

    /** Make the API call to fetch the release date */
    private Optional<String> fetchReleaseDate(String groupId, String artifactId, String version) {
        if (version.equals("${project.version}")) {
            version = project.getVersion();
        }

        URI artifactUri = URI.create(String.format(
                "%s/solrsearch/select?q=g:%s+AND+a:%s+AND+v:%s&wt=json", searchUri, groupId, artifactId, version));

        getLog().debug("Fetching " + artifactUri);

        final HttpGet httpGet = new HttpGet(artifactUri);

        try {
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                if (response.getCode() != 200) {
                    getLog().error(String.format(
                            "Failed to fetch release date for %s:%s %s (%s)",
                            groupId, artifactId, version, response.getReasonPhrase()));
                    return Optional.empty();
                }

                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                return Optional.of(responseBody);
            }
        } catch (Exception e) {
            getLog().error(String.format(
                    "Failed to fetch release date for %s:%s %s (%s)", groupId, artifactId, version, e.getMessage()));
            return Optional.empty();
        }
    }
}
