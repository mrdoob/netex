plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.threejs.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.threejs.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.material:material:1.12.0")
}

// Copy bridge.js and constants.js from the canonical /devtools folder
// into assets/ before each build, so the user keeps editing the originals.
val copyThreeDevtoolsAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../devtools")) {
        include("constants.js", "bridge.js")
    }
    into(layout.projectDirectory.dir("src/main/assets/threejs-devtools"))
}

tasks.named("preBuild") {
    dependsOn(copyThreeDevtoolsAssets)
}
