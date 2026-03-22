import com.android.build.api.dsl.Packaging
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ═════════════════════════════════════════════════════════════════════════════
// SOLUÇÃO TRIPLA para "Inconsistent JVM-target (17) vs (21)"
//
// CAMADA 1 — JVM Toolchain (ideal, requer JDK 17 instalado na máquina)
// CAMADA 2 — kotlinOptions (fallback explícito por task)
// CAMADA 3 — afterEvaluate hook (força o target em TODAS as tasks Kotlin,
//             incluindo as geradas pelo plugin Compose, que sobrescrevem o valor)
// ═════════════════════════════════════════════════════════════════════════════

// CAMADA 1
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.example.choppontap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.choppontap"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"
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

    // CAMADA 2 — compileOptions + kotlinOptions explícitos
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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

// CAMADA 3 — afterEvaluate: garante que TODAS as tasks KotlinCompile
// (incluindo as geradas pelo plugin Compose após a configuração do android {})
// usem JVM 17. Esta é a camada que resolve o problema quando o plugin Compose
// sobrescreve o jvmTarget definido nas camadas anteriores.
afterEvaluate {
    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "17"
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
    implementation("no.nordicsemi.android:ble:2.11.1")

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
