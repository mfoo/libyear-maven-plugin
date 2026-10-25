# Agent Guide: libyear-maven-plugin

This document provides essential instructions, guidelines, and context for AI coding agents and autonomous contributors working on the `libyear-maven-plugin` repository.

---

## 1. Project Overview

`libyear-maven-plugin` is an Apache Maven plugin that implements the [libyear](https://libyear.com/) dependency freshness metric. It calculates how many years behind the latest available version each project dependency is and provides summary statistics and failure thresholds (`maxLibYears`).

- **Base Lineage:** Heavily based on and derived from the [MojoHaus Versions Maven Plugin](https://www.mojohaus.org/versions/versions-maven-plugin/index.html).
- **Core Goal:** `analyze` (`io.github.mfoo:libyear-maven-plugin:analyze`).
- **License:** Apache License 2.0.

---

## 2. Requirements & Environment

- **Java:** JDK 17 minimum required. JDK 25 is actively tested and used as the default development version (defined in `.sdkmanrc` as `java=25.0.1-tem`).
- **Maven:** Apache Maven 3.9+ required.
- **Node.js / Commitlint:** Commit conventions are validated via `@commitlint/config-conventional` (configured in `commitlint.config.js`).

---

## 3. Build & Test Commands

Always verify changes using Maven from the project root:

| Command | Purpose |
|---|---|
| `mvn clean compile` | Clean output directory and compile plugin source code |
| `mvn test` | Run unit tests (JUnit 5 + Mockito) |
| `mvn spotless:check` | Verify code and POM formatting against style rules |
| `mvn spotless:apply` | Automatically reformat Java files and `pom.xml` |
| `mvn verify` | Complete verification: unit tests, formatting checks, package plugin, and execute integration tests |

### Running a Specific Test

To run an individual unit test class:
```bash
mvn test -Dtest=LibYearMojoTest
```

To run a specific test method:
```bash
mvn test -Dtest=LibYearMojoTest#testAnalyze
```

---

## 4. Integration Tests

Integration tests verify plugin execution against test Maven projects in simulated environments:
- **Location:** `src/it/`
- **Execution Engine:** `maven-invoker-plugin`
- **When run:** Automatically during `mvn verify` (or `mvn clean verify`)
- **Structure:** Each directory under `src/it/` contains a test POM (`pom.xml`) and a verification post-build hook script (`verify.bsh` or `verify.groovy`).

To run only the integration tests without all unit tests:
```bash
mvn invoker:run
```

To run a specific integration test:
```bash
mvn invoker:run -Dinvoker.test=basic-single-dependency
```

---

## 5. Code Style & Quality

The repository strictly enforces code and POM formatting using `spotless-maven-plugin`:

- **Java Code Formatting:** Formatted using [Palantir Java Format](https://github.com/palantir/palantir-java-format) with unused imports automatically removed.
- **POM Formatting:** `pom.xml` files are formatted using `sortPom` (sorting properties, dependencies, and plugins alphabetically by `groupId,artifactId`).
- **Formatting Verification:** `mvn spotless:check` is run during CI and will fail the build if any files do not adhere to formatting standards.
- **Remediation:** Always run `mvn spotless:apply` before committing your changes.

---

## 6. Commit Conventions

Commit messages must conform to the [Conventional Commits](https://www.conventionalcommits.org/) specification, enforced by `@commitlint/config-conventional`:

- **Format:** `<type>(<optional scope>): <description>`
- **Allowed Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`
- **Description:** Imperative, present tense, lowercase start, no trailing period.
- **Examples:**
  - `docs: add AGENTS.md guide for AI agents`
  - `fix: handle null release dates in dependency resolution`
  - `test: add integration test for multi-module dependencyManagement`
  - `chore(deps): bump com.google.guava:guava from 33.7.1-jre to 33.7.2-jre`

---

## 7. Workflow for AI Agents

1. **Work in an isolated branch / worktree:**
   ```bash
   git checkout -b <type>/<description>
   ```
2. **Implement changes** preserving existing architecture and conventions.
3. **Format code:**
   ```bash
   mvn spotless:apply
   ```
4. **Verify tests and builds:**
   ```bash
   mvn clean verify
   ```
5. **Commit with conventional commit message:**
   ```bash
   git commit -m "<type>: <concise description>"
   ```
6. **Open Pull Request** with a descriptive summary of changes and testing performed.
