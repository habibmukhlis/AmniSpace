import java.io.File

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val sdkDir = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
val hasNdkAndCmake = sdkDir != null &&
    File(sdkDir, "ndk").let { it.exists() && (it.listFiles()?.isNotEmpty() == true) } &&
    File(sdkDir, "cmake").let { it.exists() && (it.listFiles()?.isNotEmpty() == true) } &&
    file("src/main/cpp/CMakeLists.txt").exists()

android {
    namespace = "neth.iecal.questphone.ai"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        if (hasNdkAndCmake) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17 -O3 -fexceptions -frtti"
                    arguments += listOf("-DANDROID_STL=c++_shared")
                }
            }
        }
    }

    if (hasNdkAndCmake) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.onnxruntime.android)
    testImplementation(libs.junit)
}
