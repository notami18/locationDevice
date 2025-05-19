plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.locationdevice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.locationdevice"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

    packagingOptions {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE",
                "META-INF/NOTICE"
            )
            pickFirsts += listOf(
                "AndroidManifest.xml",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.media3.common.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Ubicación
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // AWS IoT Core SDK - versiones más estables
    implementation("com.amazonaws:aws-android-sdk-iot:2.60.0")
    implementation("com.amazonaws:aws-android-sdk-auth-core:2.60.0")
    implementation("com.amazonaws:aws-android-sdk-mobile-client:2.60.0")

    // Si necesitas MQTT específicamente, usa esta versión
    implementation("com.amazonaws:aws-android-sdk-iot:2.60.0@aar")

    // MQTT Paho - ¡Dejamos esto para usarlo en nuestra nueva implementación!
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")

    // Para BroadcastManager
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}