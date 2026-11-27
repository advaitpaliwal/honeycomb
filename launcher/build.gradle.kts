// Honeycomb: a watch-style home screen for any Android phone, installed as Android's home app (ROLE_HOME).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.honeycomb.launcher"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "org.honeycomb.launcher"
        minSdk = 34
        targetSdk = 36 // Android 16; tested on the API 36 image (AVD honeycomb-a16)
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // A lane's build installs beside the one on a shared emulator: -PidSuffix=.honeycomb
        (project.findProperty("idSuffix") as String?)?.let { applicationIdSuffix = it }
    }
    // The device tests run against debug; CI also builds them for the R8-shrunk app build type with -PtestBuildType=app
    // (developer.android.com/build/build-variants#test-build-type).
    val testBuildTypeName = providers.gradleProperty("testBuildType").getOrElse("debug")
    testBuildType = testBuildTypeName
    val releaseStore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    if (releaseStore != null) {
        signingConfigs.create("distribution") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        create("systemDemo") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
        }
        create("app") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            // R8 inlines Compose's and Kotlin's hot paths; with the baseline profile (src/main/baseline-prof.txt, generated
            // by :macrobenchmark's BaselineProfileGenerator) the journeys run compiled from the first frame. Without it the
            // tap to the grid, the full grid and opening an app each drop a frame on the emulator (perf round 2).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
            // CI's device test of this build type runs in the app's process on the app's copies of Kotlin, coroutines and
            // androidx, so that build keeps them whole; the app's own code is shrunk and optimised as shipped.
            if (testBuildTypeName == "app") proguardFiles("proguard-app-test.pro")
            // Keep developer installs working; tagged builds require the distribution identity.
            signingConfig = signingConfigs.getByName(if (releaseStore == null) "debug" else "distribution")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Battle-tested Compose drag-and-drop grid/list reordering (Calvin-LL/Reorderable, Apache 2.0, used by Lawnchair)
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    // Honeycomb's own colour scheme from the honey seed when the phone has none (jordond/MaterialKolor, MIT)
    implementation("com.materialkolor:material-kolor:2.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.window:window-core:1.3.0")
    // installs the baseline profile on sideloaded installs, where no store does it (developer.android.com/topic/performance/baselineprofiles)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
    // Compose UI tests on the device over the real home (HomeFeaturesTest, FeatureStatesTest)
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.11.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// AppListLiveTest installs the :stub app from its own assets, so the device test needs no file pushed beside it.
abstract class StubApkAsset : DefaultTask() {
    @get:InputFiles abstract val apk: ConfigurableFileCollection
    @get:OutputDirectory abstract val out: DirectoryProperty
    @TaskAction fun copy() {
        val dir = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        apk.asFileTree.matching { include("*.apk") }.singleFile.copyTo(dir.resolve("stub.apk"))
    }
}
val stubApkAsset = tasks.register<StubApkAsset>("stubApkAsset") {
    apk.from(project(":stub").layout.buildDirectory.dir("outputs/apk/debug"))
    dependsOn(":stub:assembleDebug")
}
androidComponents.onVariants { variant ->
    variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(stubApkAsset, StubApkAsset::out)
}
