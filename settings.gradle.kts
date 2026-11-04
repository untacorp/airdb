pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "airdb"

include(":airdb-adapter-api")
include(":airdb-adapter-sqlite")
include(":airdb-adapter-aerolog")
include(":airdb-protocol")
include(":airdb-transport-api")
include(":airdb-transport-sim")
include(":airdb-core")
include(":airdb-transport-ble")
include(":airdb-crypto")
include(":airdb-samples:airdb-sim-demo")
include(":airdb-samples:airdb-cli")
