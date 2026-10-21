# Installation & Setup

This guide explains how to add AirDB to your project and configure the required device permissions.

---

## 1. Add Dependencies

### Android & Kotlin JVM (`build.gradle.kts`)

Add the AirDB modules to your application module's `build.gradle.kts`:

```kotlin
dependencies {
    // Core causality engine and synchronization state machine
    implementation("io.airdb:airdb-core:0.2.0")
    
    // Wire protocol codecs, compression, and MTU fragmenter
    implementation("io.airdb:airdb-protocol:0.2.0")
    
    // ChaCha20-Poly1305 AEAD, Ed25519 signatures, and HKDF
    implementation("io.airdb:airdb-crypto:0.2.0")
    
    // Turnkey SQLite storage engine with WAL mode
    implementation("io.airdb:airdb-adapter-sqlite:0.2.0")
    
    // Production Android Bluetooth Low Energy transport driver
    implementation("io.airdb:airdb-transport-ble:0.2.0")
    
    // (Optional) In-memory transport simulator for automated unit tests
    testImplementation("io.airdb:airdb-transport-sim:0.2.0")
}
```

---

### Apple Platforms (Swift Package Manager)

#### Using Xcode GUI:
1. Open your project in Xcode.
2. Select **File > Add Package Dependencies...**
3. Enter the repository URL: `https://github.com/untacorp/airdb.git`
4. Choose the `main` branch or release tag `0.2.0`, and add `AirDbKit` to your app target.

#### Using `Package.swift`:
```swift
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MyApp",
    dependencies: [
        .package(url: "https://github.com/untacorp/airdb.git", from: "0.2.0")
    ],
    targets: [
        .target(
            name: "MyApp",
            dependencies: [
                .product(name: "AirDbKit", package: "airdb")
            ]
        )
    ]
)
```

---

## 2. Configure Permissions

### Android Manifest Permissions

Add the following to your `app/src/main/AndroidManifest.xml`:

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
> On Android 12 and above, remember to request runtime permissions for `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, and `BLUETOOTH_CONNECT` before calling `engine.start()`. AirDB provides a helper class `AndroidBlePermissions` in `:airdb-transport-ble` to verify granted statuses.

---

### Apple `Info.plist` Configuration

On iOS, iPadOS, and macOS, add the Bluetooth usage description key to your app's `Info.plist`:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>AirDB uses Bluetooth Low Energy to discover nearby peers and synchronize database records offline.</string>
```

If you plan to sync changes while the application is in the background, also enable background modes in your target's **Signing & Capabilities**:
- **Uses Bluetooth LE accessories** (Central role)
- **Acts as a Bluetooth LE accessory** (Peripheral role)

---

## Next Steps

Now that your project has the required dependencies and permissions, continue to the [5-Minute Quickstart](quickstart.md) to initialize your first database and start syncing!
