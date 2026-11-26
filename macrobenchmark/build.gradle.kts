// Frame timing for Honeycomb's journeys, and the baseline profile those journeys generate (Jetpack Macrobenchmark,
// developer.android.com/topic/performance/benchmarking/macrobenchmark-overview). Runs against the release-like
// "app" build: ./gradlew :macrobenchmark:connectedAppAndroidTest
plugins {
    id("com.android.test")
}
android {
    namespace = "org.honeycomb.macrobenchmark"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        // matches the launcher's release-like "app" build type, which is what the benchmark measures
        create("app") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    targetProjectPath = ":launcher"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "app" }
}
dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
