plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tv.ball"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tv.ball"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名配置：优先使用 CI 注入的正式密钥；
    // 未提供密钥时回退 debug 签名，保证 Release 产物仍可安装
    val releaseStorePath = System.getenv("TVPLAYER_STORE_FILE")
    val hasReleaseKey = !releaseStorePath.isNullOrBlank() && File(releaseStorePath).exists()

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = System.getenv("TVPLAYER_STORE_PASSWORD")
                keyAlias = System.getenv("TVPLAYER_KEY_ALIAS")
                keyPassword = System.getenv("TVPLAYER_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
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
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
    
    // 只打包 arm64-v8a 架构，加快编译速度
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")  // 只包含 arm64-v8a（现代设备）
            isUniversalApk = false  // 不生成通用 APK
        }
    }
    
    buildFeatures {
        viewBinding = true
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
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    
    // Activity & Fragment
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    
    // ExoPlayer
    implementation(libs.exoplayer.core)
    implementation(libs.exoplayer.ui)
    implementation(libs.exoplayer.hls)
    
    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    
    // Glide
    implementation(libs.glide)
    ksp(libs.glide.compiler)
    
    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    
    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")
    
    // NanoHTTPD for local web server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
        
    // Hilt removed - using manual DI
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    // Navigation
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    
    // SwipeRefreshLayout
    implementation(libs.androidx.swiperefreshlayout)
    
    // ViewPager2 for Banner
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}