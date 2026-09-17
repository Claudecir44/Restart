plugins {
    alias(libs.plugins.android.application)
}

// google-services precisa de app/google-services.json pra sequer configurar
// (senão o build inteiro falha na fase de configuração) — mesma
// salvaguarda do Caronas: enquanto o projeto Firebase do Restart não
// existir/o arquivo não for baixado do console, o app continua compilando
// normalmente, só sem a parte de IA na nuvem funcionar em runtime.
val temGoogleServices = file("google-services.json").exists()
if (temGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.cjstudio.restart"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cjstudio.restart"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

configurations.all {
    resolutionStrategy {
        // Mesmo ajuste dos outros projetos (ver app/build.gradle.kts do
        // Match/Caronas): o compilador Kotlin embutido no AGP 9 é fixo numa
        // versão; algumas libs mais novas puxam um kotlin-stdlib mais
        // recente do que esse compilador entende.
        force("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core)

    // IA na nuvem (ver functions/index.js) — Auth só pra identificar quem
    // chama (login anônimo, sem tela de cadastro — ver RestartApplication)
    // e Functions pra chamar iniciarProcessamento/verificarProcessamento.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.functions)

    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime.ktx)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Corrige orientação da foto a partir do EXIF antes de processar —
    // fotos vindas da câmera/galeria costumam vir "deitadas" com só a tag
    // EXIF dizendo a rotação certa (ver RestauracaoUtil.corrigirOrientacao).
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
