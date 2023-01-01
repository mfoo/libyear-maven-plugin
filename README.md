![GitHub Action build status badge](https://github.com/mfoo/libyear-maven-plugin/actions/workflows/maven-tests.yml/badge.svg)

# libyear-maven-plugin

This plugin helps you see how outdated your Maven project dependencies are via the
[libyear](https://libyear.com/) dependency freshness measure:

```
[INFO] -------------------< com.mfoot:libyear-maven-plugin >-------------------
[INFO] Building libyear-maven-plugin Maven Mojo 0.0.1-SNAPSHOT
[INFO] ----------------------------[ maven-plugin ]----------------------------
[INFO]
[INFO] --- libyear-maven-plugin:0.0.1-SNAPSHOT:libyear-report (default-cli) @ libyear-maven-plugin ---
[INFO]
[INFO] The following dependencies in Dependencies have newer versions:
[INFO]   org.apache.maven.plugin-tools:maven-plugin-annotations . 0.79 libyears
[INFO]   org.apache.maven.resolver:maven-resolver-api ........... 1.56 libyears
[INFO]   org.apache.maven:maven-compat .......................... 0.52 libyears
[INFO]   org.apache.maven.wagon:wagon-provider-api .............. 0.98 libyears
[INFO]   org.apache.maven:maven-model ........................... 0.52 libyears
[INFO]   org.apache.maven:maven-plugin-api ...................... 0.52 libyears
[INFO]   org.mockito:mockito-junit-jupiter ...................... 0.04 libyears
[INFO]   org.apache.maven:maven-artifact ........................ 0.52 libyears
[INFO]   org.apache.maven:maven-core ............................ 0.52 libyears
[INFO]
[INFO] Total years outdated: 5.96
```

The plugin requires JDK 11+. It is heavily based on the [MojoHaus Versions Maven Plugin](https://www.mojohaus.org/versions/versions-maven-plugin/index.html).

## Usage

The plugin provides a single goal, `libyear-report`. This can be executed
directly via the command-line with Maven, or it can be incorporated as part of
a build inside `pom.xml`.

### Command-line

```shell
mvn com.mfoot:libyear-maven-plugin:0.0.1-SNAPSHOT:libyear-report
```

### Plugin execution

```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.mfoot</groupId>
      <artifactId>libyear-maven-plugin</artifactId>
      <version>0.0.1-SNAPSHOT</version>
      <executions>
        <execution>
          <id>libyear-report</id>
          <goals>
            <goal>libyear-report</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```