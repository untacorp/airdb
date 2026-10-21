# AirDB Package Publishing & Distribution Guide

This guide outlines the complete pipeline for publishing and distributing AirDB across the Android/JVM ecosystem (Maven Central / GitHub Packages) and the Apple ecosystem (Swift Package Manager / Swift Package Index).

---

## 1. Distribution Ecosystem Overview

| Ecosystem | Primary Package Manager | Distribution Channel | Configuration File |
|---|---|---|---|
| **Android & Kotlin/JVM** | Gradle / Maven | Maven Central (Sonatype) & GitHub Packages | `build.gradle.kts` (`maven-publish` plugin) |
| **Apple (iOS/macOS/watchOS)** | Swift Package Manager (SPM) | Git Release Tags & Swift Package Index (SPI) | `Package.swift` & `AirDbKit/.spi.yml` |

---

## 2. Android & Kotlin/JVM Publishing Pipeline

AirDB includes 8 published JVM modules:
- `:airdb-adapter-api`
- `:airdb-adapter-sqlite`
- `:airdb-core`
- `:airdb-crypto`
- `:airdb-protocol`
- `:airdb-transport-api`
- `:airdb-transport-ble`
- `:airdb-transport-sim`

### Step 1: Local Verification (`publishToMavenLocal`)

To verify POM generation, sources JARs, and javadoc JARs locally without publishing to external servers:

```bash
cd airdb
./gradlew publishToMavenLocal
```

This installs artifacts to your local cache at `~/.m2/repository/io/airdb/`. You can inspect the generated `.pom`, `-sources.jar`, and `.jar` files.

### Step 2: Sonatype Central / Maven Central Preparation

Maven Central requires cryptographically signed artifacts (GPG) and valid POM metadata (license, developers, SCM):

1. **Create an account** on [Sonatype Central Portal](https://central.sonatype.com/).
2. **Claim Namespace**: Verify ownership of `io.airdb` or `io.github.untacorp` via DNS TXT record or GitHub repository verification.
3. **Generate GPG Key**:
   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
   ```
4. **Export GPG Private Key for CI**:
   ```bash
   gpg --armor --export-secret-keys <YOUR_KEY_ID> | base64 -w 0
   ```
5. **Configure CI Secrets**:
   In GitHub repository settings (`Settings > Secrets and variables > Actions`):
   - `MAVEN_CENTRAL_USERNAME`: User token from Sonatype Central Portal.
   - `MAVEN_CENTRAL_PASSWORD`: Password token from Sonatype Central Portal.
   - `SIGNING_KEY`: Base64-encoded GPG private key.
   - `SIGNING_PASSWORD`: Passphrase for the GPG key.

### Step 3: Publishing to Maven Central

Run the publish task:

```bash
./gradlew publishAllPublicationsToMavenCentralRepository
```

### Step 4: Publishing to GitHub Packages

AirDB is pre-configured to publish snapshots and releases to GitHub Packages:

```bash
export GITHUB_ACTOR="untacorp"
export GITHUB_TOKEN="<YOUR_GITHUB_PAT>"
./gradlew publishAllPublicationsToGitHubPackagesRepository
```

---

## 3. Apple Platform Distribution (Swift Package Manager)

Unlike Android/JVM, Apple platforms embrace a **decentralized package distribution model**. There is no central Maven-like server required.

### How Developers Import AirDbKit

Any iOS, macOS, watchOS, or visionOS developer can add AirDB in Xcode:
1. Open Xcode, select **File > Add Package Dependencies...**
2. Enter repository URL:
   ```
   https://github.com/untacorp/airdb.git
   ```
3. Select version rule: **Up to Next Major from 0.2.0**.

Or in `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/untacorp/airdb.git", from: "0.2.0")
]
```

### Swift Package Index (SPI)

To make AirDB discoverable on [SwiftPackageIndex.com](https://swiftpackageindex.com) and enable native Apple DocC documentation hosting:

1. AirDB includes an [`.spi.yml`](file:///home/auttomus/Documents/Code/PROJECT/ble-research/airdb/AirDbKit/.spi.yml) configuration file declaring build matrix targets:
   - iOS 15.0+
   - macOS 12.0+
   - watchOS 8.0+
   - tvOS 15.0+
2. The repository is indexed automatically by Swift Package Index webhooks.
3. Developers can browse official Apple-styled documentation directly on SPI.

### Creating a SemVer Release Tag

To create a new version release for both SPM and SPI:

```bash
# 1. Ensure working tree is clean and all tests pass
git status
./gradlew test
cd AirDbKit && swift test

# 2. Create git tag conforming to SemVer
git tag -a v0.2.0 -m "Release v0.2.0: Zero-Trust Cryptographic Mesh & Durable SQLite Persistence"

# 3. Push tag to origin
git push origin v0.2.0
```

---

## 4. Release Checklist

Before tagging or deploying any new release:
- [ ] Ensure all unit tests pass on both platforms: `./gradlew test` and `cd AirDbKit && swift test`.
- [ ] Run zero-emoji compliance scanner across all files.
- [ ] Update `CHANGELOG.md` with release notes and migration details.
- [ ] Verify `publishToMavenLocal` completes with 0 errors.
- [ ] Push git tag and verify GitHub Actions CI workflow triggers and finishes green.
