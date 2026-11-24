plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.test.retry) apply false
}

// On GitHub (CI set), a failed test runs once more: a timing flake passes the second time, a real
// failure fails twice. Both show in the report, so flaky tests can still be found and fixed.
// Locally a failure stays a failure.
if (System.getenv("CI") != null) {
    subprojects {
        apply(plugin = "org.gradle.test-retry")
        tasks.withType<Test>().configureEach {
            extensions.configure<org.gradle.testretry.TestRetryTaskExtension> {
                maxRetries.set(1)
                maxFailures.set(5)
                failOnPassedAfterRetry.set(false)
            }
        }
    }
}
