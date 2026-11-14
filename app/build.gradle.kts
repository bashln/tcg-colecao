plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Um APK público assinado com a chave de debug nunca recebe atualização da release real,
// porque o Android recusa trocar a assinatura. Por isso o empacotamento de release falha
// sem keystore, em vez de cair para debug em silêncio. TCG_ALLOW_DEBUG_SIGNING=1 libera
// o fallback só para um build local de teste.
val releaseKeystore = System.getenv("TCG_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.exists() }
val allowDebugSigning = System.getenv("TCG_ALLOW_DEBUG_SIGNING") == "1"
val releasePackagingRequested = gradle.startParameter.taskNames.any { requested ->
    when (requested.substringAfterLast(':')) {
        "assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle" -> true
        else -> false
    }
}

val missingKeystoreMessage =
    "release-build: keystore de release ausente. Defina TCG_KEYSTORE_PATH, " +
        "TCG_KEYSTORE_PASSWORD, TCG_KEY_ALIAS e TCG_KEY_PASSWORD, ou use " +
        "TCG_ALLOW_DEBUG_SIGNING=1 para um APK de teste assinado com debug."

// BuildConfig carrega valores de ambiente (Supabase). O build cache não trata variável de
// ambiente como entrada e já devolveu um BuildConfig vazio com os secrets certos no CI. Gerar
// de novo custa milissegundos e evita publicar um APK sem conta.
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("BuildConfig") }.configureEach {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

if (releasePackagingRequested && releaseKeystore == null && !allowDebugSigning) {
    throw GradleException(missingKeystoreMessage)
}

// `build` e `assemble` também empacotam a variante release sem aparecer no nome da tarefa.
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }.configureEach {
    doFirst {
        if (releaseKeystore == null && !allowDebugSigning) throw GradleException(missingKeystoreMessage)
    }
}


android {
    namespace = "app.tcg.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.tcg.app"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("TCG_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("TCG_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0"
        // Alvo Supabase opcional. A chave publishable é um identificador público de cliente
        // (protegido por RLS), lida do ambiente e nunca commitada; vazio = sync desativado.
        buildConfigField("String", "SUPABASE_URL", "\"${System.getenv("TCG_SUPABASE_URL") ?: ""}\"")
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${System.getenv("TCG_SUPABASE_PUBLISHABLE_KEY") ?: ""}\"",
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = releaseKeystore
                storePassword = System.getenv("TCG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TCG_KEY_ALIAS")
                keyPassword = System.getenv("TCG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // O build de teste convive com o APK de release assinado no mesmo aparelho, então
            // a correção pode ser comparada sem desinstalar a versão publicada nem apagar dados.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 fica desligado até uma execução em aparelho provar o APK encolhido. Um release
            // público não estreia com shrinking que nunca rodou.
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.ktor.client.mock)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
