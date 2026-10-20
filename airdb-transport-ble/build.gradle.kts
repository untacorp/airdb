import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val androidJarFile: File? = run {
    var sdkPath: String? = null
    val localProps = File(rootDir, "local.properties")
    if (localProps.exists()) {
        val props = Properties()
        localProps.inputStream().use { props.load(it) }
        sdkPath = props.getProperty("sdk.dir")
    }
    if (sdkPath == null) {
        sdkPath = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    }
    if (sdkPath == null) {
        val defaultDir = File(System.getProperty("user.home"), "SDK/Android")
        if (defaultDir.exists()) sdkPath = defaultDir.absolutePath
    }
    val jar = sdkPath?.let { File(it, "platforms/android-34/android.jar") }
    if (jar != null && jar.exists()) {
        jar
    } else {
        val platformsDir = sdkPath?.let { File(it, "platforms") }
        platformsDir?.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("android-") }
            ?.sortedByDescending { it.name }
            ?.map { File(it, "android.jar") }
            ?.firstOrNull { it.exists() }
    }
}

dependencies {
    implementation(project(":airdb-transport-api"))
    implementation(project(":airdb-protocol"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)

    if (androidJarFile != null) {
        compileOnly(files(androidJarFile))
        testCompileOnly(files(androidJarFile))
    }

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
