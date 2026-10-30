#!/usr/bin/env bash
set -Eeuo pipefail

# EngagementProject bootstrap
# Creates a minimal Java 21 / Spring Boot 3.5.x / Maven project foundation.
# It does not stage, commit, reset, or clean Git state.

EXPECTED_PROJECT_PATH="${EXPECTED_PROJECT_PATH:-/c/PROJECTS/JavaCode/java21code/EngagementProject}"
MAVEN_WRAPPER_VERSION="${MAVEN_WRAPPER_VERSION:-3.9.11}"
BASE_PACKAGE="com.jgo.engagement"

fail() {
  printf '\nERROR: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '\n=== %s ===\n' "$*"
}

# Resolve the current directory. This script is intended for Git Bash on Windows.
command -v cygpath >/dev/null 2>&1 || fail "This script expects Git Bash on Windows (cygpath was not found)."
CURRENT_PATH="$(cygpath -am "$PWD")"
EXPECTED_WINDOWS_PATH="$(cygpath -am "$EXPECTED_PROJECT_PATH")"

[[ "${CURRENT_PATH,,}" == "${EXPECTED_WINDOWS_PATH,,}" ]] ||
  fail "Run this script from '$EXPECTED_PROJECT_PATH'. Current directory: '$CURRENT_PATH'"

[[ -d .git ]] || fail "The current directory does not contain its own .git directory. No files were created."

# Refuse to overwrite any expected foundation files.
protected_paths=(
  "pom.xml"
  "mvnw"
  "mvnw.cmd"
  ".mvn"
  "src/main/java/com/jgo/engagement/EngagementApplication.java"
  "src/test/java/com/jgo/engagement/EngagementApplicationTest.java"
)
for path in "${protected_paths[@]}"; do
  [[ ! -e "$path" ]] || fail "Refusing to overwrite existing path: $path"
done

info "Checking Java 21"
command -v java >/dev/null 2>&1 || fail "Java was not found on PATH. Configure a JDK 21 and reopen Git Bash."
JAVA_VERSION_OUTPUT="$(java -version 2>&1)" || fail "java -version failed."
printf '%s\n' "$JAVA_VERSION_OUTPUT"
grep -Eq 'version "21([.]|")' <<< "$JAVA_VERSION_OUTPUT" ||
  fail "Java 21 is required; the detected Java version is not 21."

info "Checking Maven"
command -v mvn >/dev/null 2>&1 || fail "Maven (mvn) was not found on PATH. No project files were created."
mvn -version || fail "mvn -version failed."

# Check that the wrapper plugin can be resolved before creating files.
info "Checking Maven Wrapper plugin"
mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.2:wrapper \
  "-Dmaven=${MAVEN_WRAPPER_VERSION}" -DskipTests ||
  fail "Maven Wrapper plugin check failed. No project files were created."

info "Creating project structure"
mkdir -p \
  "src/main/java/com/jgo/engagement" \
  "src/main/resources" \
  "src/test/java/com/jgo/engagement"

cat > pom.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath/>
    </parent>

    <groupId>com.jgo</groupId>
    <artifactId>engagement</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>EngagementProject</name>
    <description>Template-publish fan-out worker</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
XML

cat > src/main/java/com/jgo/engagement/EngagementApplication.java <<'JAVA'
package com.jgo.engagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EngagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngagementApplication.class, args);
    }
}
JAVA

cat > src/test/java/com/jgo/engagement/EngagementApplicationTest.java <<'JAVA'
package com.jgo.engagement;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EngagementApplicationTest {

    @Test
    void applicationContextLoads() {
    }
}
JAVA

info "Generating Maven Wrapper"
mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.2:wrapper \
  "-Dmaven=${MAVEN_WRAPPER_VERSION}" ||
  fail "Maven Wrapper generation failed after project files were created. Review them before retrying; this script will not overwrite them."

[[ -x ./mvnw ]] || chmod +x ./mvnw

info "Running clean verify"
./mvnw clean verify || fail "Build or tests failed. Review the Maven output; this script intentionally refuses to overwrite files on rerun."

info "Git status (read-only)"
git status --short --branch || printf 'Warning: git status could not be read.\n' >&2

printf '\nBootstrap completed successfully. No files were staged or committed.\n'
