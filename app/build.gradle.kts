import com.android.build.api.dsl.Packaging

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ─────────────────────────────────────────────────────────────────────────────
// JVM TOOLCHAIN — solução definitiva para o erro:
//   "Inconsistent JVM-target compatibility detected (17) vs (21)"
//
// O Kotlin 2.x usa JVM 21 por padrão. O bloco kotlin { jvmToolchain(17) }
// força AMBOS os compiladores (javac + kotlinc) a usar o mesmo JDK 17,
// eliminando o conflito de uma vez por todas, sem precisar de kotlinOptions.
// ─────────────────────────────────────────────────────────────────────────────
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

    // compileOptions mantido para compatibilidade explícita com bibliotecas Java
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
            // ─────────────────────────────────────────────────────────────────
            // ANTECIPAÇÃO: conflito de META-INF entre jjwt + okhttp + gson
            // Sem este bloco, o build falha com:
            //   "More than one file was found with OS independent path
            //    'META-INF/DEPENDENCIES'"
            // ─────────────────────────────────────────────────────────────────
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
    // ANTECIPAÇÃO: java-jwt 3.10.3 tem vulnerabilidade CVE-2022-23529.
    // Atualizado para 4.4.0 (API compatível, sem breaking changes para uso básico).
    implementation("com.auth0:java-jwt:4.4.0")

    // ANTECIPAÇÃO: jjwt 0.7.0 é muito antigo e causa conflito de classes com
    // java-jwt. Substituído pela versão modular 0.12.6 que não conflita.
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // ── BLE ───────────────────────────────────────────────────────────────────
    // ANTECIPAÇÃO: nordic BLE 2.11.0 pode ter conflito de R class com AGP 8.9.
    // Atualizado para 2.11.1 (última estável compatível com compileSdk 35).
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
