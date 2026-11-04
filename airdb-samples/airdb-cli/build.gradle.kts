plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":airdb-core"))
    implementation(project(":airdb-crypto"))
    implementation(project(":airdb-adapter-api"))
    implementation(project(":airdb-adapter-sqlite"))
    implementation(project(":airdb-protocol"))
    implementation(project(":airdb-transport-api"))
    implementation(project(":airdb-transport-ble"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation("org.slf4j:slf4j-nop:2.0.12")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("io.airdb.cli.AirDbCliKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
