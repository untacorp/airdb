// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "AirDbKit",
    platforms: [
        .iOS(.v15),
        .macOS(.v12),
        .watchOS(.v8),
        .tvOS(.v15)
    ],
    products: [
        .library(
            name: "AirDbKit",
            targets: ["AirDbKit"]
        )
    ],
    dependencies: [],
    targets: [
        .target(
            name: "AirDbKit",
            dependencies: [],
            path: "AirDbKit/Sources/AirDbKit"
        ),
        .testTarget(
            name: "AirDbKitTests",
            dependencies: ["AirDbKit"],
            path: "AirDbKit/Tests/AirDbKitTests"
        )
    ]
)
