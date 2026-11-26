// A fixture app installed and removed by the device tests: one launcher activity (AppListLiveTest proves the grid follows
// package changes while the home is open) and widget providers of every shape (WidgetFitTest, WidgetZooShots). Never shipped.
plugins { id("com.android.application") }
android {
    namespace = "org.honeycomb.stub"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "com.example.livestub" // outside org.honeycomb., which the grid hides (isInternal)
        minSdk = 34
        targetSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
