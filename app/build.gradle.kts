plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hee6635.linkmemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hee6635.linkmemo"
        minSdk = 29
        targetSdk = 35
        versionCode = 23
        versionName = "0.23"
        // 영상 도구(yt-dlp · ffmpeg)가 크기 때문에 요즘 폰용(arm64)만 넣음
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // 시험용 고정 키: 매번 같은 키로 서명 → 업데이트해도 저장한 메모가 그대로 남음
        create("test") {
            storeFile = rootProject.file("keystore/test.keystore")
            storePassword = "linkmemo"
            keyAlias = "linkmemo"
            keyPassword = "linkmemo"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("test") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
        }
    }
    packaging {
        // yt-dlp(파이썬) · ffmpeg 실행 파일이 압축 해제된 상태로 있어야 함
        jniLibs { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 영상 · 설명 · 댓글 받기 (yt-dlp)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    // 일반 웹페이지 본문 읽기
    implementation("org.jsoup:jsoup:1.18.1")
    // 캡처 화면 글자 읽기 (폰 안에서, 한국어)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
}
