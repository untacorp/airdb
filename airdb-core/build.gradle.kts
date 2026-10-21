plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":airdb-adapter-api"))
    implementation(project(":airdb-crypto"))
    implementation(project(":airdb-protocol"))
    implementation(project(":airdb-transport-api"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)

    testImplementation(project(":airdb-transport-sim"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
