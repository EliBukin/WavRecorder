plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wavrecorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wavrecorder"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Robolectric's ParcelFileDescriptor shadow reflectively duplicates a real
            // java.io.FileDescriptor's private fd field when backing a SAF-opened channel; JDK 17+'s
            // strict module encapsulation blocks that reflection by default (only under this
            // specific real-descriptor path -- most shadows don't need it), silently breaking
            // FileChannel.close() on the affected descriptor. Previously invisible here because
            // nothing checked close()'s own result/exception; WavRecoveryManager's recovery pass now
            // does (see its close-failure handling), which is what surfaced this environment gap.
            all {
                it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    // fragment-testing's EmptyFragmentActivity must be merged into the manifest Robolectric
    // loads for local (JVM) unit tests, which is the debug app manifest, not a test-only one —
    // hence debugImplementation rather than testImplementation. This is the setup Google's own
    // FragmentScenario + Robolectric docs call for.
    debugImplementation("androidx.fragment:fragment-testing:1.8.2")
}
