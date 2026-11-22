import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional release signing, configured through git-ignored local.properties.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseStoreFile = localProps.getProperty("pocketslm.keystore")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

// Optional experimental Vulkan backend. Default OFF; pass
// -Ppocketllm.vulkan=true to build the Vulkan-enabled APK.
val enableVulkan = listOf("pocketllm.vulkan", "POCKETLLM_ENABLE_VULKAN")
    .mapNotNull { project.findProperty(it)?.toString()?.toBooleanStrictOrNull() }
    .any { it }

android {
    namespace = "com.pocketllm"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.pocketllm"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"

        // 1B+ GGUF models need a 64-bit address space; no armeabi-v7a on purpose.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                if (enableVulkan) arguments += listOf("-DPOCKETLLM_ENABLE_VULKAN=ON")
                // Forward optional dependency overrides, e.g. -DPOCKETLLM_HOST_CC=...
                listOf(
                    "POCKETLLM_VULKAN_HEADERS_DIR",
                    "POCKETLLM_VULKAN_GLSLC",
                    "POCKETLLM_VULKAN_LIBRARY",
                    "POCKETLLM_SPIRV_HEADERS_INCLUDE_DIR",
                    "POCKETLLM_HOST_COMPILER_DIR",
                    "POCKETLLM_HOST_CC",
                    "POCKETLLM_HOST_CXX",
                ).forEach { name ->
                    project.findProperty(name)?.let { arguments += "-D$name=$it" }
                }
                cppFlags += listOf("-O3")
            }
        }
    }

    sourceSets {
        getByName("main") {
            if (enableVulkan) jniLibs.srcDir("src/main/vulkan-jniLibs")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = localProps.getProperty("pocketslm.storePassword")
                keyAlias = localProps.getProperty("pocketslm.keyAlias")
                keyPassword = localProps.getProperty("pocketslm.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Runtime CPU kernel variants are opened from the native library dir.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation("junit:junit:4.13.2")

    debugImplementation(libs.compose.ui.tooling)
}
