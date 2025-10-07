plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.opencv"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
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
    
    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf("libs"))
            java.setSrcDirs(listOf("src/main/java"))
            manifest.srcFile(file("src/main/AndroidManifest.xml"))
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("${project.projectDir}/libcxx_helper/CMakeLists.txt")
            version = "3.22.1"
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
    // Add any dependencies if needed
}

// This is needed to include the OpenCV .so files
configurations.maybeCreate("default")
artifacts.add("default", file("libs/opencv-4.8.0.aar"))
