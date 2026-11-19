plugins {
    alias(libs.plugins.android.application)
}

android {
    buildFeatures { buildConfig = true }
    namespace = "com.example.valuamsecurity"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.valuamsecurity"
        minSdk = 24
        targetSdk = 37
        buildConfigField("String", "API_BASE_URL", "\"" + providers.gradleProperty("valuamApiUrl").orElse("https://13.216.100.242/valuam/index.php").get() + "\"")
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    implementation("com.github.f0ris.sweetalert:library:1.6.2")
    implementation("com.android.volley:volley:1.2.1")

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation("com.airbnb.android:lottie:6.7.1")
}
