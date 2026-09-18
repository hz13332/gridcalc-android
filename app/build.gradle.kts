plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.gridcalc.gridcalc"
    compileSdk = 33

    defaultConfig {
        applicationId = "cn.gridcalc.gridcalc"
        minSdk = 26
        targetSdk = 33
        versionCode = 20000
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}
