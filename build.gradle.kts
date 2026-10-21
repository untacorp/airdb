plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    `maven-publish`
    signing
}

subprojects {
    if (!path.startsWith(":airdb-samples")) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        apply(plugin = "java")

        group = "io.github.untacorp"
        version = "0.2.0"

        configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }

        val publishing = extensions.getByType<PublishingExtension>()
        val signing = extensions.getByType<SigningExtension>()

        publishing.publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])

                pom {
                    name.set(project.name)
                    description.set("AirDB: Decentralized, Local-First P2P Database Synchronization Engine over BLE")
                    url.set("https://github.com/untacorp/airdb")

                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }

                    developers {
                        developer {
                            id.set("airdb-authors")
                            name.set("AirDB Authors")
                            email.set("maintainers@airdb.dev")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/untacorp/airdb.git")
                        developerConnection.set("scm:git:ssh://github.com/untacorp/airdb.git")
                        url.set("https://github.com/untacorp/airdb")
                    }
                }
            }
        }

        publishing.repositories {
            maven {
                name = "staging"
                url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
            }
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/untacorp/airdb")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: ""
                    password = System.getenv("GITHUB_TOKEN") ?: ""
                }
            }
        }

        val signingKey = System.getenv("GPG_PRIVATE_KEY")
        val signingPassword = System.getenv("GPG_PASSPHRASE") ?: ""
        if (!signingKey.isNullOrBlank()) {
            signing.useInMemoryPgpKeys(signingKey, signingPassword)
            signing.sign(publishing.publications["mavenJava"])
        } else {
            signing.isRequired = false
        }
    }
}
