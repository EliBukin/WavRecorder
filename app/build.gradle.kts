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
    // Fragment UI tests (FragmentScenario) live in the debug unit-test source set, src/testDebug:
    // FragmentScenario hosts every fragment in EmptyFragmentActivity, an exported test activity
    // that Robolectric only resolves from the tested variant's merged app manifest -- and whose
    // theme resource and R class exist only where that manifest artifact is an app dependency. A
    // release build must never ship it, so it is merged into debug builds only (Google's
    // documented setup), and FragmentScenario itself is on the debug unit-test classpath only.
    // Every other test lives in src/test and runs for both debug and release.
    testDebugImplementation("androidx.fragment:fragment-testing:1.8.2")
    debugImplementation("androidx.fragment:fragment-testing-manifest:1.8.2")
}
