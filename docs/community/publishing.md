# Package Distribution & Publishing Guide

This guide outlines the pipeline for publishing AirDB artifacts to Maven Central and the Swift Package Index.

---

## 1. JVM & Android Publishing (Maven Central)

AirDB publishes 8 JVM modules to Sonatype Maven Central:
- `:airdb-adapter-api`
- `:airdb-adapter-sqlite`
- `:airdb-core`
- `:airdb-crypto`
- `:airdb-protocol`
- `:airdb-transport-api`
- `:airdb-transport-ble`
- `:airdb-transport-sim`

### Local Verification (`publishToMavenLocal`)
To verify POM generation, sources JARs, and javadoc JARs locally without publishing to remote repositories:

```bash
cd airdb
./gradlew publishToMavenLocal
```

This installs artifacts to your local cache at `~/.m2/repository/io/github/untacorp/`.

---

## 2. Apple Publishing (Swift Package Manager)

`AirDbKit` is distributed as a Swift Package directly via Git release tags:

1. Create a version tag:
   ```bash
   git tag -s v0.2.0 -m "Release v0.2.0"
   git push origin v0.2.0
   ```
2. Swift Package Index automatically monitors tags and indexes documentation based on `.spi.yml`.
