// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "AblaMobileIos",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "AblaMobileModel", targets: ["AblaMobileModel"]),
        .library(name: "AblaMobileHost", targets: ["AblaMobileHost"]),
    ],
    targets: [
        .target(name: "AblaMobileModel"),
        .target(name: "CAblaMobile", publicHeadersPath: "include"),
        .target(name: "AblaMobileHost", dependencies: ["AblaMobileModel", "CAblaMobile"]),
        .testTarget(name: "AblaMobileModelTests", dependencies: ["AblaMobileModel"]),
    ]
)
