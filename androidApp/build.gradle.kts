import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

// FCM (APP-17) necesita el google-services.json del proyecto Firebase del equipo. Mientras no
// exista, la app compila igual y simplemente no recibe pushes (ver docs/notificaciones-destete.md).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.agrotech.oviapp.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.agrotech.oviapp.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        val apiBaseUrl = providers.gradleProperty("baapp.apiBaseUrl").getOrElse("")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.kotlinx.datetime)
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.fragment)
    implementation(libs.ktor.client.core)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation(kotlin("test-junit"))
}
