plugins {
    id("com.android.application")
}

android {
    namespace = "org.abla.mobile.showcase"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.abla.mobile.showcase"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DABLA_COMPILER_ROOT=${rootProject.layout.projectDirectory.dir("../ablac").asFile.absolutePath}"
                arguments += "-DABLA_APP_OBJECT=${project.layout.projectDirectory.file("src/main/obj/arm64-v8a/abla_app.o").asFile.absolutePath}"
            }
        }
    }

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
