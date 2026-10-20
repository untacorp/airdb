plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":airdb-core"))
    implementation(project(":airdb-adapter-api"))
    implementation(project(":airdb-adapter-sqlite"))
    implementation(project(":airdb-protocol"))
    implementation(project(":airdb-transport-api"))
    implementation(project(":airdb-transport-sim"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
}

application {
    mainClass.set("io.airdb.demo.DisasterReliefDemoKt")
}
