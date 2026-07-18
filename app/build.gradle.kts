plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "wx.mirage"
    compileSdk = 34

    buildFeatures {
        buildConfig = false
    }
    
    defaultConfig {
        applicationId = "wx.mirage"
        minSdk = 26
        targetSdk = 34
        versionCode = 101
        versionName = "1.0.1"
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
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
    
    packaging {
        resources {
            excludes += "META-INF/**"
        }
    }
}

dependencies {
    // Xposed API (compileOnly, 运行时由 Xposed 框架提供)
    compileOnly("de.robv.android.xposed:api:82")
    
    // DexKit - 动态查找微信混淆类
    implementation("org.luckypray:dexkit:2.0.1")
    
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // Gson
    implementation("com.google.code.gson:gson:2.10.1")
}
