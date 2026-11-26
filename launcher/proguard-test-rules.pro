# The device tests, shrunk by R8 for the app build type (-PtestBuildType=app).
# Error Prone's annotations (via Guava in androidx.test) name javax.lang.model, which Android does not have.
-dontwarn javax.lang.model.**
