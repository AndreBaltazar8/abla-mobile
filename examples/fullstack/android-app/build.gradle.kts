import java.util.Properties

plugins {
    id("com.android.application")
}

val ablaMobileConfiguration = providers.gradleProperty("ablaMobileConfiguration")
    .orElse("debug")
    .get()
val ablaMobileRoot = layout.buildDirectory.dir(
    "abla-mobile/$ablaMobileConfiguration"
).get().asFile
val ablaMobilePropertiesFile = ablaMobileRoot.resolve("application.properties")
check(ablaMobilePropertiesFile.isFile) {
    "Missing ${ablaMobilePropertiesFile.path}; run the Abla mobile build program first"
}
val ablaMobile = Properties().apply {
    ablaMobilePropertiesFile.inputStream().use(::load)
}
fun ablaMobileInt(name: String): Int =
    requireNotNull(ablaMobile.getProperty(name)) {
        "Missing Abla Mobile property: $name"
    }.toInt()
fun ablaMobileString(name: String): String =
    requireNotNull(ablaMobile.getProperty(name)) {
        "Missing Abla Mobile property: $name"
    }

android {
    namespace = ablaMobileString("namespace")
    compileSdk = ablaMobileInt("compileSdk")
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = ablaMobileString("applicationId")
        minSdk = ablaMobileInt("minSdk")
        targetSdk = ablaMobileInt("targetSdk")
        versionCode = ablaMobileInt("versionCode")
        versionName = ablaMobileString("versionName")

        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DABLA_COMPILER_ROOT=${rootProject.layout.projectDirectory.dir("../../../ablac").asFile.absolutePath}"
                arguments += "-DABLA_APP_OBJECT=${ablaMobileRoot.resolve(ablaMobileString("applicationObject")).absolutePath}"
            }
        }
    }

    sourceSets["main"].manifest.srcFile(
        ablaMobileRoot.resolve(ablaMobileString("manifest"))
    )
    sourceSets["main"].res.srcDir(
        ablaMobileRoot.resolve(ablaMobileString("resources"))
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":runtime:android-host"))
}
