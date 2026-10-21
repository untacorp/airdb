# Installation & Setup

This guide provides instructions for adding AirDB dependencies to your Android, JVM, or Apple projects, choosing the appropriate modules, and configuring the required device permissions.

---

## 1. Add Dependencies

AirDB is distributed through **Maven Central** and **GitHub Packages** for Android/JVM, and through **Swift Package Manager (SPM)** for Apple platforms.

=== "Android & JVM (Maven Central)"

    ### Step 1: Configure Repositories
    Ensure `mavenCentral()` is declared in your root `settings.gradle.kts`:

    ```kotlin
    dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        repositories {
            google()
            mavenCentral()
        }
    }
    ```

    ### Step 2: Add Dependencies
    In your app module's `build.gradle.kts`, declare the required AirDB modules under the verified namespace `io.github.untacorp`:

    ```kotlin
    dependencies {
        // Core synchronization engine and Hybrid Logical Clock
        implementation("io.github.untacorp:airdb-core:0.2.0")
        
        // Zero-Trust cryptographic mesh (ChaCha20-Poly1305, Ed25519, HKDF)
        implementation("io.github.untacorp:airdb-crypto:0.2.0")
        
        // Turnkey SQLite storage engine with Write-Ahead Logging (WAL)
        implementation("io.github.untacorp:airdb-adapter-sqlite:0.2.0")
        
        // Android Bluetooth Low Energy hardware transport driver
        implementation("io.github.untacorp:airdb-transport-ble:0.2.0")
        
        // (Optional) Wire protocol codec (pulled transitively by core)
        implementation("io.github.untacorp:airdb-protocol:0.2.0")

        // (Optional) In-memory network simulator for automated unit tests
        testImplementation("io.github.untacorp:airdb-transport-sim:0.2.0")
    }
    ```

=== "Android & JVM (GitHub Packages)"

    If your organization prefers fetching dependencies directly from GitHub Packages:

    ### Step 1: Configure GitHub Packages Repository
    Add the untacorp GitHub Packages endpoint to `settings.gradle.kts`:

    ```kotlin
    dependencyResolutionManagement {
        repositories {
            google()
            mavenCentral()
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/untacorp/airdb")
                credentials {
                    username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                    password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
    ```

    ### Step 2: Declare Dependencies
    Add dependencies in `build.gradle.kts` using the same coordinates:

    ```kotlin
    dependencies {
        implementation("io.github.untacorp:airdb-core:0.2.0")
        implementation("io.github.untacorp:airdb-crypto:0.2.0")
        implementation("io.github.untacorp:airdb-adapter-sqlite:0.2.0")
        implementation("io.github.untacorp:airdb-transport-ble:0.2.0")
    }
    ```

=== "Apple Platforms (Swift Package Manager)"

    `AirDbKit` supports iOS 15.0+, macOS 12.0+, tvOS 15.0+, and watchOS 8.0+ with **zero third-party dependencies**.

    ### Option A: Using Xcode GUI
    1. In Xcode, open your project or workspace.
    2. Select **File > Add Package Dependencies...**
    3. In the search field (top right), enter the repository URL:
       ```
       https://github.com/untacorp/airdb.git
       ```
    4. Set the **Dependency Rule** to **Up to Next Major from `0.2.0`** (or **Exact `0.2.0`**).
    5. Choose your app target for `AirDbKit` and click **Add Package**.

    ### Option B: Using `Package.swift`
    If you are developing a Swift package or modular app, declare `AirDbKit` in your `Package.swift`:

    ```swift
    // swift-tools-version: 5.9
    import PackageDescription

    let package = Package(
        name: "MyOfflineApp",
        platforms: [
            .iOS(.v15),
            .macOS(.v12)
        ],
        dependencies: [
            .package(url: "https://github.com/untacorp/airdb.git", from: "0.2.0")
        ],
        targets: [
            .target(
                name: "MyOfflineApp",
                dependencies: [
                    .product(name: "AirDbKit", package: "airdb")
                ]
            )
        ]
    )
    ```

    ### Discovery on Swift Package Index
    `AirDbKit` is indexed on the [Swift Package Index](https://swiftpackageindex.com) for automated compatibility checks, API documentation, and platform support matrices.

---

## 2. Module Selection Matrix

AirDB is intentionally modular to prevent mobile binary bloat and separate platform-specific hardware code from pure business logic:

| Module | Typical Profile | Description |
| :--- | :--- | :--- |
| `airdb-core` | Required (All) | HLC clocks, vector clocks, 4-phase reconciliation state machine, and engine facade. |
| `airdb-crypto` | Recommended (All) | RFC 8439 ChaCha20-Poly1305 AEAD, RFC 8032 Ed25519 signatures, and RFC 5869 HKDF. |
| `airdb-adapter-sqlite` | Android / JVM Apps | Production SQLite adapter with Write-Ahead Logging (WAL) and tombstone lifecycle management. |
| `airdb-transport-ble` | Android Apps | Hardware BLE transport driver with single-flight GATT operation queue and MTU negotiation. |
| `airdb-adapter-api` | Custom Adapters | Pure interface for custom database engines (Room, SQLDelight, Realm, MongoDB). |
| `airdb-transport-api` | Custom Transports | Pure interface for custom network physical layers (Wi-Fi Direct, WebSockets, LoRa). |
| `airdb-transport-sim` | Unit Tests | Sans-I/O in-memory network simulator for instant, deterministic multi-node mesh tests. |
| `AirDbKit` | Apple (All-in-One) | Unified Swift package containing Core, Crypto, SQLite, and CoreBluetooth drivers. |

---

## 3. Configure Hardware Permissions

### Android Manifest Permissions

Add the following permission declarations to `app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- BLE Radio Access for Android 12+ (API 31+) -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                     android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <!-- Legacy BLE Radio Access for Android 8.0 - 11 (API 26-30) -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

    <!-- Declare Bluetooth Low Energy hardware requirement -->
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

</manifest>
```

> [!NOTE]
> On Android 12 and above, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, and `BLUETOOTH_CONNECT` are **runtime permissions**. You must request them from the user before starting the radio. Use the built-in helper `AndroidBlePermissions.checkAndRequest()` in `:airdb-transport-ble` to verify granted status.

---

### Apple `Info.plist` Configuration

In your Xcode target settings (or `Info.plist`), specify the Bluetooth usage description:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>AirDB uses Bluetooth Low Energy to discover nearby peers and synchronize database records offline.</string>
```

#### Background Synchronization Modes
To allow AirDB to replicate records while the iOS app is in the background:
1. Open your target's **Signing & Capabilities** tab in Xcode.
2. Add the **Background Modes** capability.
3. Check both:
   * **Uses Bluetooth LE accessories** (Central role)
   * **Acts as a Bluetooth LE accessory** (Peripheral role)

---

## 4. Cryptographic Signature Verification

All AirDB release artifacts are cryptographically signed to guarantee authenticity and supply-chain integrity:
- **Signer Identity**: `Agus Arya <kadekaguspnata@gmail.com>`
- **GPG Key ID**: `CEB164B84F724DCC`
- **GPG Key Fingerprint**: `11FF 32A2 9FE9 E6E3 14A4 8894 CEB1 64B8 4F72 4DCC`
- **Keyserver Verification**: `keyserver.ubuntu.com` and `keys.openpgp.org`

To verify detached signatures locally:
```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys CEB164B84F724DCC
gpg --verify airdb-core-0.2.0.jar.asc airdb-core-0.2.0.jar
```

---

## Next Steps

Now that your project has the required dependencies and permissions configured:
- Jump into the **[5-Minute Quickstart](quickstart.md)** to initialize the engine and sync your first record.
- Explore the **[Apple Platform Guide](apple-airdbkit.md)** for idiomatic SwiftUI and Swift actor integration.
