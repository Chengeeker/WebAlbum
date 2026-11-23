plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.webdav.album"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { applicationId = "com.webdav.album"; minSdk = 26; targetSdk = 36; versionCode = 46; versionName = "1.5.8"; ndk { abiFilters += "arm64-v8a" } }
    signingConfigs { create("release") { storeFile = file(providers.environmentVariable("WEBALBUM_KEYSTORE").getOrElse("missing.p12")); storePassword = providers.environmentVariable("WEBALBUM_KEY_PASSWORD").orNull; keyAlias = "infinitycm"; keyPassword = providers.environmentVariable("WEBALBUM_KEY_PASSWORD").orNull; storeType = "PKCS12" } }
    buildTypes { release { signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(project(":lucent-ui"))
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.5.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.2")
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-session:1.9.2")
    implementation("com.google.android.material:material:1.14.0") // GreenMusic artwork tonal-spot palette
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16")
}

tasks.register("verifyComposeAlignment") {
    doLast {
        fun versions(name: String) = configurations.getByName(name).incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }.filter { it.group.startsWith("androidx.compose.") }
            .associate { "${it.group}:${it.name}" to it.version }
        val compile = versions("releaseCompileClasspath")
        val runtime = versions("releaseRuntimeClasspath")
        val mismatches = compile.filter { (module, version) -> runtime[module] != version }
        check(mismatches.isEmpty()) { "Compose compile/runtime mismatch: $mismatches" }
        println("Compose alignment verified: ${compile.size} modules")
    }
}
