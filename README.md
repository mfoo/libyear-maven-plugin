![GitHub Action build status badge](https://github.com/mfoo/libyear-maven-plugin/actions/workflows/maven-tests.yml/badge.svg)
[![Apache License, Version 2.0, January 2004](https://img.shields.io/github/license/mojohaus/versions-maven-plugin.svg?label=License)](http://www.apache.org/licenses/)

# libyear-maven-plugin

This plugin helps you see how outdated your Maven project dependencies are via the
[libyear](https://libyear.com/) dependency freshness measure:

```
[INFO] -------------------< io.github.mfoo:libyear-maven-plugin >-------------------
[INFO] Building libyear-maven-plugin Maven Mojo 0.0.1-SNAPSHOT
[INFO] ----------------------------[ maven-plugin ]----------------------------
[INFO]
[INFO] --- libyear-maven-plugin:0.0.1-SNAPSHOT:analyze (default-cli) @ libyear-maven-plugin ---
[INFO]
[INFO] The following dependencies in Dependencies have newer versions:
[INFO]   org.apache.httpcomponents:httpclient ................... 2.15 libyears
[INFO]   org.apache.httpcomponents:httpcore ..................... 0.98 libyears
[INFO]   org.apache.maven:maven-settings ........................ 0.52 libyears
[INFO]   org.junit.jupiter:junit-jupiter-engine ................. 0.31 libyears
[INFO]
[INFO] Total years outdated: 3.96
```

The plugin requires JDK 11+. It is heavily based on the [MojoHaus Versions Maven Plugin](https://www.mojohaus.org/versions/versions-maven-plugin/index.html).

## Usage

The plugin provides a single goal, `analyze`. This can be executed
directly via the command-line with Maven, or it can be incorporated as part of
a build inside `pom.xml`.

### Command-line

```shell
mvn io.github.mfoo:libyear-maven-plugin:0.0.1-SNAPSHOT:analyze
```

### Plugin execution

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.github.mfoo</groupId>
      <artifactId>libyear-maven-plugin</artifactId>
      <version>0.0.1-SNAPSHOT</version>
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