plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.tabatabata"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.tabatabata"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "APPMETRICA_KEY",
            "\"${providers.gradleProperty("appMetricaKey").orElse("").get()}\"",
        )
        buildConfigField(
            "String",
            "BANNER_ID",
            "\"${providers.gradleProperty("bannerId").orElse("").get()}\"",
        )
        buildConfigField(
            "String",
            "INTERSTITIAL_ID",
            "\"${providers.gradleProperty("interstitialId").orElse("").get()}\"",
        )
        buildConfigField(
            "String",
            "SUPPORT_EMAIL",
            "\"${providers.gradleProperty("supportEmail").orElse("").get()}\"",
        )
        buildConfigField(
            "String",
            "PRIVACY_URL",
            "\"${providers.gradleProperty("privacyUrl").orElse("").get()}\"",
        )
    }
    val keyPath = providers.environmentVariable("TABATA_KEYSTORE_PATH").orNull
    if (!keyPath.isNullOrBlank()) {
        val production =
            signingConfigs.create("production") {
                storeFile = file(keyPath)
                storePassword = providers.environmentVariable("TABATA_STORE_PASSWORD").get()
                keyAlias =
                    providers.environmentVariable("TABATA_KEY_ALIAS").orElse("tabata-bata").get()
                keyPassword = providers.environmentVariable("TABATA_KEY_PASSWORD").get()
            }
        buildTypes.getByName("release").signingConfig = production
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("io.appmetrica.analytics:analytics:8.6.0")
    implementation("com.yandex.android:mobileads:8.6.0")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
