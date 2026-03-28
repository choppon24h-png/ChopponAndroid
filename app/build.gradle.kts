plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.choppontap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.choppontap"
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "2.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {

    // ── AndroidX ─────────────────────────────────────────────────────────────
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.runtime.ktx)

    // ── Compose ───────────────────────────────────────────────────────────────
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)

    // ── Rede ──────────────────────────────────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── JSON ──────────────────────────────────────────────────────────────────
    implementation("com.google.code.gson:gson:2.10.1")

    // ── JWT ───────────────────────────────────────────────────────────────────
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // ── BLE ───────────────────────────────────────────────────────────────────
    implementation("no.nordicsemi.android:ble:2.6.1")

    // ── QR Code ───────────────────────────────────────────────────────────────
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // ── Testes ────────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}
