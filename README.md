[![Apache License, Version 2.0, January 2004](https://img.shields.io/github/license/mojohaus/versions-maven-plugin.svg?label=License)](http://www.apache.org/licenses/)
![GitHub Action build status badge](https://github.com/mfoo/libyear-maven-plugin/actions/workflows/maven-tests.yml/badge.svg)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=mfoo_libyear-maven-plugin&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=mfoo_libyear-maven-plugin)
[![Known Vulnerabilities](https://snyk.io/test/github/mfoo/libyear-maven-plugin/badge.svg)](https://snyk.io/test/github/mfoo/libyear-maven-plugin)

# libyear-maven-plugin

This Maven plugin implements the [libyear](https://libyear.com/) dependency
freshness measure, showing you how many years behind the latest version each of
your dependencies are.

When executed, you'll see an output like this for each of your modules:
```
[INFO] The following dependencies in Dependencies have newer versions:
[INFO]   io.quarkus:quarkus-arc ................................. 0.27 libyears
[INFO]   io.quarkus:quarkus-junit5 .............................. 0.27 libyears
[INFO]   io.quarkus:quarkus-junit5-mockito ...................... 0.27 libyears
[INFO]   io.quarkus:quarkus-rest-client ......................... 0.27 libyears
[INFO]   io.quarkus:quarkus-resteasy ............................ 0.27 libyears
[INFO]   io.quarkus:quarkus-resteasy-jackson .................... 0.27 libyears
[INFO]   io.rest-assured:rest-assured ........................... 0.77 libyears
[INFO]   org.mockito:mockito-all ................................ 0.00 libyears
[INFO]   org.mockito:mockito-core ............................... 0.35 libyears
[INFO] 
[INFO] This module is 2.73 libyears behind
```

The plugin requires JDK 11+. It is heavily based on the [MojoHaus Versions Maven Plugin](https://www.mojohaus.org/versions/versions-maven-plugin/index.html).

## Usage

The plugin provides a single goal, `analyze`. This can be executed
directly via the command-line with Maven, or it can be incorporated as part of
a build inside `pom.xml`.

### Standalone

```shell
mvn io.github.mfoo:libyear-maven-plugin:analyze
```

### As part of a build

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.github.mfoo</groupId>
      <artifactId>libyear-maven-plugin</artifactId>
      <version>1.1.0</version>
      <executions>
        <execution>
          <id>libyear-analysis</id>
          <goals>
            <goal>analyze</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

## Configuration
Configuration options can be specified on the command line such as
`-DdependencyExcludes="io.github.mfoo:*"` or as part of the plugin
configuration in `pom.xml`.

| Option                 | Description                                                  | Format                          |
|------------------------|--------------------------------------------------------------|---------------------------------|
| `dependencyExcludes`   | Ignore certain dependencies                                  | `io.github.mfoo:*,org.apache:*` |
| `maxLibYears`          | Cause the build to fail if dependencies are older than this  | `4`                             |
| `reportFile`           | The path to report file                                      | `target/libyear-report.txt`     |
| `minLibYearsForReport` | Minimum age of the dependencies to be included in the report | `2`                             |


A full list of options can be seen as part of the [plugin documentation
site](https://mfoo.github.io/libyear-maven-plugin/analyze-mojo.html).