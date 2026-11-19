plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// native 直通层开关，见 gradle.properties
val withNative = (project.findProperty("withNative") as String? ?: "true").toBoolean()

android {
    namespace = "com.lcs.hifi"
    compileSdk = 34
    // 与 CI 安装的 NDK 版本保持一致，避免 AGP 去下载未安装的默认版本
    if (withNative) {
        ndkVersion = "25.1.8937393"
    }

    defaultConfig {
        applicationId = "com.lcs.hifi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        if (withNative) {
            ndk {
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
            }
            externalNativeBuild {
                cmake {
                    arguments.addAll(listOf("-DANDROID_STL=c++_static"))
                }
            }
        }
    }

    if (withNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
