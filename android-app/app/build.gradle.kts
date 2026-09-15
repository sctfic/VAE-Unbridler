plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alban.ebike"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alban.ebike"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.core:core:1.15.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
