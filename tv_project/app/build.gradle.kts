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
        // 开发版通道：CI 把 run_number 注入成 DEV_BUILD_NUMBER，
        // 应用内更新用它判断「有没有比手上这个更新的 dev 构建」。
        // versionCode 保持静态递增，避免离线构建与 CI 构建互相覆盖安装。
        val devBuildNumber = (
            System.getenv("TVPLAYER_DEV_BUILD_NUMBER")
                ?: project.findProperty("TVPLAYER_DEV_BUILD_NUMBER") as String?
            )?.toIntOrNull() ?: 0

        versionCode = 36
        versionName = "1.2.33"
        buildConfigField("int", "DEV_BUILD_NUMBER", "$devBuildNumber")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名配置：优先使用 CI 注入的正式密钥（环境变量或 Gradle 属性）；
    // 未提供密钥时回退 debug 签名，保证 Release 产物仍然可安装。
    signingConfigs {
        // 固定 debug 签名：本地与 CI 用同一把密钥，APK 可直接覆盖安装，
        // 不再出现"每次 CI 构建签名都不一样导致无法覆盖安装"的问题。
        getByName("debug") {
            storeFile = file("debug-signing.p12")
            storeType = "PKCS12"
            val DEBUG_STORE_PASSWORD = "android"
            val DEBUG_KEY_ALIAS = "androiddebugkey"
            val DEBUG_KEY_PASSWORD = "android"
            storePassword = DEBUG_STORE_PASSWORD
            keyAlias = DEBUG_KEY_ALIAS
            keyPassword = DEBUG_KEY_PASSWORD
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 关闭混淆：播放器 / Glide 反射较多，保持可运行优先
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
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
        buildConfig = true
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
    implementation(libs.exoplayer.dash)
    
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

    // NanoHTTPD：局域网投屏用的本地 Web 服务
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // ViewPager2：首页分类分页 / 轮播容器
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // SwipeRefreshLayout
    implementation(libs.androidx.swiperefreshlayout)

    // 应用内更新：Shizuku（adb 级安装兜底）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
