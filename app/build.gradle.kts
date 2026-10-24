plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.seupainel.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seupainel.player"
        minSdk = 21
        targetSdk = 35
        // versionCode = número do build no CI (sobe a cada push), permite o app
        // detectar que há versão nova. Local = 1.
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.0.$run"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        // Chave de depuração FIXA (versionada no repo). Garante que todas as
        // atualizações tenham a mesma assinatura e instalem por cima sem o erro
        // "conflito com pacote já existente".
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // O motor P2P (libtvcore.so / libgojni.so) e o glue com.tvbus.engine
    // entram via jniLibs/ e libs/ — fornecidos pelo seu provedor de painel.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // DNS-over-HTTPS: resolve hosts que o DNS da TV/operadora bloqueia (ex.: .sbs)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    // Decodificação por software (FFmpeg) — opcional, espelha o original.
    // implementation("androidx.media3:media3-decoder-ffmpeg:$media3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.leanback:leanback:1.0.0")
    // Carregamento de imagens (pôsteres de filmes/séries)
    implementation("io.coil-kt:coil:2.7.0")
    // Instala o Baseline Profile (AOT dos caminhos quentes) no 1º uso.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
