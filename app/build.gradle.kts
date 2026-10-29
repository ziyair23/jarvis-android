plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val withHome = rootProject.file("homesdk").exists()

android {
    namespace = "com.ziyair.jarvis"
    compileSdk = 35
    sourceSets["main"].java.srcDir(if (withHome) "src/home/java" else "src/nohome/java")

    defaultConfig {
        applicationId = "com.ziyair.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("boolean", "HOME_SDK", withHome.toString())
    }

    signingConfigs {
        create("jarvis") {
            storeFile = file("jarvis.keystore")
            storePassword = "jarvis123"
            keyAlias = "jarvis"
            keyPassword = "jarvis123"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("jarvis") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }

    buildFeatures { buildConfig = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES") } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    if (withHome) {
        implementation("com.google.android.gms:play-services-home:17.1.0")
        implementation("com.google.android.gms:play-services-home-types:17.1.0")
        implementation("androidx.activity:activity-ktx:1.9.3")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    }
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
    implementation("org.apache.commons:commons-compress:1.26.2")
}
