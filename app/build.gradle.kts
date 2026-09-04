plugins {
    id("com.android.application")
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
        versionCode = 110
        versionName = "1.1.0"
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

    // baseutils（core 与 base aar 同 namespace，AGP9 冲突；
    // 两 jar 类零重叠，故提取 classes.jar 到 libs 直接引用）
    implementation(files("libs/base-utils.jar", "libs/base-utils-core.jar"))
    implementation("com.github.Mingyueyixi.lposed:xposed-api2:a88cf0c651")
    implementation("com.github.Mingyueyixi.lposed:plugin:a88cf0c651")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")

    // wcdb（编译期引用，运行时由微信进程提供）
    compileOnly("com.tencent.wcdb:wcdb-android:1.1-19")
}
