import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Optional: put fyers.appId, fyers.secret and fyers.redirectUri in local.properties (never committed) so the APK you build
// for someone else is pre-configured and they only tap "Log in". Only SHA-256(appId:secret) goes into the APK, never the secret.
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
val fyersAppId: String = localProps.getProperty("fyers.appId", "").trim()
val fyersSecret: String = localProps.getProperty("fyers.secret", "").trim()

// Each UI branch builds its own app, so all three install side by side; later builds of a branch install as updates.
// version2 (and any other branch) is com.papertrade.app "ALPHA"; grow and Angel add a suffix and a name. The version
// code is the branch's commit count, so it only goes up.
fun git(vararg args: String): String = runCatching {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrDefault("")
val branch = git("rev-parse", "--abbrev-ref", "HEAD")
val (idSuffix, appLabel) = when (branch) { "grow" -> ".grow" to "ALPHA Grow"; "Angel" -> ".angel" to "ALPHA Angel"; else -> "" to "ALPHA" }
val commitCount = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1

android {
    namespace = "com.papertrade.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.papertrade.app$idSuffix"
        minSdk = 26
        targetSdk = 35
        versionCode = commitCount
        versionName = "0.1.$commitCount"
        manifestPlaceholders["appLabel"] = appLabel
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FYERS_APP_ID", "\"$fyersAppId\"")
        buildConfigField("String", "FYERS_APP_ID_HASH", "\"${if (fyersAppId.isNotEmpty() && fyersSecret.isNotEmpty()) sha256("$fyersAppId:$fyersSecret") else ""}\"")
        buildConfigField("String", "FYERS_REDIRECT_URI", "\"${localProps.getProperty("fyers.redirectUri", "").trim()}\"")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.security.crypto)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
