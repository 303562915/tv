plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// ─────────────────────────────────────────────────────────────────────────────
// Release 签名占位配置
// 仓库内不保存任何证书或口令，全部由环境变量 / Gradle 属性注入：
//   TVPLAYER_STORE_FILE      证书文件路径（相对 app 模块或绝对路径）
//   TVPLAYER_STORE_PASSWORD  证书库口令
//   TVPLAYER_KEY_ALIAS       密钥别名
//   TVPLAYER_KEY_PASSWORD    密钥口令
//
// 本地构建：
//   1) 把 keystore 放到 app/release.jks（已被 .gitignore 忽略，不要提交）
//   2) 在 ~/.gradle/gradle.properties 中写入上述四个属性，或直接设置同名环境变量
// GitHub Actions：
//   把四项作为 Repository Secrets，再在 workflow 里导出为环境变量即可。
//
// 四项缺任意一项时：不创建 release 签名配置，产物为 app-release-unsigned.apk，
// 构建不会失败，便于 PR 校验与 fork 构建。
// ─────────────────────────────────────────────────────────────────────────────
fun secret(name: String): String? =
    (System.getenv(name) ?: project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

val releaseStoreFile: String? = secret("TVPLAYER_STORE_FILE")
val releaseStorePassword: String? = secret("TVPLAYER_STORE_PASSWORD")
val releaseKeyAlias: String? = secret("TVPLAYER_KEY_ALIAS")
val releaseKeyPassword: String? = secret("TVPLAYER_KEY_PASSWORD")
val hasReleaseSigning: Boolean = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.tv.ball"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tv.ball"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // 只有注入了完整签名信息时才使用 release 签名，否则输出 unsigned APK
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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