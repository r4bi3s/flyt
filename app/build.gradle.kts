plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "no.heimflyt.launcher"
    compileSdk = 36
    defaultConfig {
        applicationId = "no.heimflyt.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        // Release outputs are unsigned on purpose: keys never enter the Gradle build. See RELEASING.md.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Google-encrypted dependency metadata belongs in the Play bundle only, not in the GitHub APK.
    dependenciesInfo { includeInApk = false; includeInBundle = true }
    lint { abortOnError = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui:1.9.3")
    implementation("androidx.compose.foundation:foundation:1.9.3")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}

val verifyDebugManifest = tasks.register("verifyDebugManifest") {
    dependsOn("processDebugManifest")
    doLast {
        val file = layout.buildDirectory.file("intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml").get().asFile
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(file)
        val ns = "http://schemas.android.com/apk/res/android"
        fun elements(tag: String) = doc.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element } }
        val expected = "no.heimflyt.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        check(elements("uses-permission").map { it.getAttributeNS(ns,"name") }.toSet() == setOf(expected,"android.permission.READ_CONTACTS","android.permission.INTERNET","android.permission.SET_WALLPAPER","android.permission.CAMERA")) { "Unexpected permissions in merged manifest" }
        val app=elements("application").single()
        check(app.getAttributeNS(ns,"networkSecurityConfig")=="@xml/network_security_config") { "network_security_config missing" }
        check(layout.projectDirectory.file("src/main/res/xml/network_security_config.xml").asFile.readText().contains("cleartextTrafficPermitted=\"false\"")) { "cleartext must be disabled" }
        check(elements("permission").single().getAttributeNS(ns,"protectionLevel") == "signature")
        for(tag in listOf("service","receiver","activity-alias")) check(elements(tag).isEmpty()) { "Unexpected $tag" }
        // Exactly one provider: the non-exported FileProvider for Share theme (cache/exports only).
        val providers=elements("provider")
        check(providers.size==1 && providers[0].getAttributeNS(ns,"name")=="androidx.core.content.FileProvider" && providers[0].getAttributeNS(ns,"exported")=="false" &&
            providers[0].getAttributeNS(ns,"authorities")=="no.heimflyt.launcher.exports") { "Unexpected provider" }
        val activity=elements("activity").single()
        check(activity.getAttributeNS(ns,"name")=="no.heimflyt.launcher.MainActivity")
        check(activity.getAttributeNS(ns,"exported")=="true" && activity.getAttributeNS(ns,"launchMode")=="singleTask")
        check(elements("application").single().getAttributeNS(ns,"allowBackup")=="false")
        check(elements("category").any { it.getAttributeNS(ns,"name")=="android.intent.category.HOME" })
        println("Manifest allowlist passed: one HOME activity, READ_CONTACTS + INTERNET + SET_WALLPAPER + CAMERA + AndroidX signature permission, cleartext disabled, one private export FileProvider, no background components.")
    }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn(verifyDebugManifest) }
