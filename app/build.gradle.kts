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
        // 版本号:本地默认 20004；CI 传入 -PciBuildNumber 后为 30000+run，保证单调递增可覆盖安装
        val ciRun = (findProperty("ciBuildNumber") as String?)?.toIntOrNull()
        versionCode = if (ciRun != null) 30000 + ciRun else 20004
        versionName = "2.0.4"
    }

    // 固定签名:本地与 CI 共用同一把钥匙，同签名才能覆盖安装免卸载
    // 注意:keystore 与口令随仓库公开，仅适用于自用分发；上架应用商店请换正式签名
    signingConfigs {
        create("shared") {
            storeFile = file("../keystore/gridcalc.jks")
            storePassword = "gridcalc2026"
            keyAlias = "gridcalc"
            keyPassword = "gridcalc2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
