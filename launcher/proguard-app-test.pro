# Only for CI's device test of the app build type (-PtestBuildType=app), never the shipped APK: the test APK leaves out the
# libraries the app already has and calls the app's copies, which R8 trims to what the app alone calls
# (androidx.tracing.Trace, kotlinx.coroutines.JobKt, androidx.compose.ui.platform.InfiniteAnimationPolicy were missing),
# and calls app classes only tests use (Setup). The app's own code stays optimised, with its members kept for the test.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.** { *; }
-keep,allowoptimization class org.honeycomb.launcher.** { *; }
