pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
    }
}
rootProject.name = "Honeycomb"
include(":launcher") // watch-style home (default App)
include(":stub") // test fixture: a codeless app AppListLiveTest installs and removes
include(":macrobenchmark") // frame timing + baseline profile (Jetpack Macrobenchmark)
