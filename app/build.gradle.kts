import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(propertyName: String, environmentName: String): String? =
    providers.environmentVariable(environmentName).orNull?.takeIf(String::isNotBlank)
        ?: releaseSigningProperties.getProperty(propertyName)?.takeIf(String::isNotBlank)

val releaseStorePath = releaseSigningValue("storeFile", "CLOUD_PORTAL_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "CLOUD_PORTAL_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "CLOUD_PORTAL_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "CLOUD_PORTAL_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() } && releaseStorePath?.let { rootProject.file(it).isFile } == true

abstract class ProductionCheckTask : DefaultTask() {
    @get:Input
    abstract val signingReady: Property<Boolean>

    @get:InputFile
    abstract val releaseBundle: RegularFileProperty

    @TaskAction
    fun verifyProductionBundle() {
        check(signingReady.get()) {
            "Missing production signing credentials. Copy keystore.properties.example to " +
                "keystore.properties or provide the CLOUD_PORTAL_* environment variables."
        }
        check(releaseBundle.get().asFile.isFile) { "Release Android App Bundle was not generated." }
    }
}

android {
    namespace = "com.trijohn.cloudportal"
    //noinspection GradleDependency -- API 37 is not in the stable SDK channel yet.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trijohn.cloudportal"
        minSdk = 29
        //noinspection OldTargetApi -- API 36 is the current Google Play production target.
        targetSdk = 36
        versionCode = 14
        versionName = "2.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("production") {
                storeFile = rootProject.file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            // This build is installable with the standard debug key but must not expose
            // iCloud's private WebView cookie store through `adb run-as`.
            isDebuggable = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("production")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
    }
}

dependencies {
    // Compose 1.11.x is the newest stable line that remains compatible with API 36.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    //noinspection GradleDependency -- Core 1.19 requires the unreleased API 37 SDK.
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.register<ProductionCheckTask>("productionCheck") {
    group = "verification"
    description = "Runs release checks and verifies that the upload bundle is signed."
    dependsOn("testDebugUnitTest", "lintRelease", "bundleRelease")
    signingReady.set(releaseSigningReady)
    releaseBundle.set(layout.buildDirectory.file("outputs/bundle/release/app-release.aab"))
}
