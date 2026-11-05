import java.time.LocalDate
import java.util.Properties
import java.util.regex.Pattern

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("dev.detekt")
    id("kotlin-parcelize")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries.android)
}

android {
    namespace = "com.kylecorry.trail_sense"
    compileSdk = 37

    defaultConfig {
        vectorDrawables.useSupportLibrary = true
        applicationId = "com.wanderwildwood.chikei"
        minSdk = 24
        targetSdk = 37
        versionCode = 27
        versionName = "0.2.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // Falling Fruit's API key for the Forage layer, from the gitignored signing/ folder. With
        // no file, or anything there that is not six to sixteen letters and digits, the build has
        // no key and Forage shows OpenStreetMap only.
        val fallingFruitKey = rootProject.file("signing/fallingfruit.key")
            .takeIf { it.isFile }?.readText()?.trim()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9]{6,16}")) && it != "ABCD1234" }
            ?: ""
        buildConfigField("String", "FALLING_FRUIT_KEY", "\"$fallingFruitKey\"")
    }
    // A real keystore in signing/ signs every build type when it is present, so the very
    // first install is already release-signed and a later update can never hit
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE. It is gitignored, and there is no fallback: a fresh
    // clone builds an unsigned release APK, which will not install anywhere.
    val signingPropertiesFile = rootProject.file("signing/signing.properties")
    val realSigningConfig = if (signingPropertiesFile.isFile) {
        val signingProperties = Properties().apply {
            signingPropertiesFile.inputStream().use(::load)
        }
        signingConfigs.create("real") {
            storeFile = rootProject.file("signing/signing.keystore")
            storePassword = signingProperties.getProperty("STORE_PASSWORD")
            keyAlias = signingProperties.getProperty("KEY_ALIAS")
            keyPassword = signingProperties.getProperty("KEY_PASSWORD")
        }
    } else {
        null
    }
    signingConfigs {
        create("nightly") {
            if (System.getProperty("nightly_store_file") != null) {
                storeFile = file(System.getProperty("nightly_store_file"))
                storePassword = System.getProperty("nightly_store_password")
                keyAlias = System.getProperty("nightly_key_alias")
                keyPassword = System.getProperty("nightly_key_password")
            }
        }
        create("github") {
            if (System.getProperty("github_store_file") != null) {
                storeFile = file(System.getProperty("github_store_file"))
                storePassword = System.getProperty("github_store_password")
                keyAlias = System.getProperty("github_key_alias")
                keyPassword = System.getProperty("github_key_password")
            }
        }
    }
    androidResources {
        // Support for auto-generated locales for per-app language settings
        generateLocaleConfig = true
    }
    buildFeatures {
        // Support for view binding
        viewBinding = true
        buildConfig = true
    }
    buildTypes {
        // ------- RELEASES -------
        // F-Droid
        getByName("release") {
            realSigningConfig?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            // AGP stamps the git revision into META-INF. The build box works from an rsync with
            // no .git, so the same version built in two places would differ. Off.
            vcsInfo {
                include = false
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Play Store
        create("playStore") {
            initWith(getByName("release"))
        }
        // GitHub
        create("github") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("github")
        }

        // ------- DEVELOPMENT -------
        // Local debug
        getByName("debug") {
            realSigningConfig?.let { signingConfig = it }
            testProguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Staging
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
        }

        // ------- NIGHTLY -------

        // Debug
        create("nightly") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("nightly")
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-nightly-${LocalDate.now()}"
        }
        // Release
        create("nightlyRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("nightly")
            applicationIdSuffix = ".nightly_release"
            versionNameSuffix = "-nightly-release-${LocalDate.now()}"
        }
    }
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources.merges += "META-INF/LICENSE.md"
        resources.merges += "META-INF/LICENSE-notice.md"
        jniLibs {
            useLegacyPackaging = true
        }
    }


    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$projectDir/detekt-baseline.xml")
    source.setFrom("src/main/java", "src/main/kotlin")
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    jvmTarget = JavaVersion.VERSION_11.toString()
}

dependencies {
    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // The shared About dialog and Licences list (Compose, drawn in Mudita's MMD)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.mmd)

    // AndroidX
    implementation(libs.constraintlayout)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.documentfile)
    implementation(libs.gridlayout)
    implementation(libs.preference.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    ksp(libs.room.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.process)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.androidx.core.remoteviews)
    implementation(libs.androidx.exifinterface)

    // Material
    implementation(libs.material)
    implementation(libs.flexbox)

    // Andromeda
    implementation(libs.andromeda.core)
    implementation(libs.andromeda.fragments)
    implementation(libs.andromeda.forms)
    implementation(libs.andromeda.csv)
    implementation(libs.andromeda.background)
    implementation(libs.andromeda.camera)
    implementation(libs.andromeda.gpx)
    implementation(libs.andromeda.sound)
    implementation(libs.andromeda.sense)
    implementation(libs.andromeda.signal)
    implementation(libs.andromeda.preferences)
    implementation(libs.andromeda.permissions)
    implementation(libs.andromeda.canvas)
    implementation(libs.andromeda.files)
    implementation(libs.andromeda.notify)
    implementation(libs.andromeda.alerts)
    implementation(libs.andromeda.pickers)
    implementation(libs.andromeda.list)
    implementation(libs.andromeda.qr)
    implementation(libs.andromeda.markdown)
    implementation(libs.andromeda.camera)
    implementation(libs.andromeda.clipboard)
    implementation(libs.andromeda.haptics)
    implementation(libs.andromeda.bitmaps)
    implementation(libs.andromeda.torch)
    implementation(libs.andromeda.battery)
    implementation(libs.andromeda.compression)
    implementation(libs.andromeda.pdf)
    implementation(libs.andromeda.exceptions)
    implementation(libs.andromeda.print)
    implementation(libs.andromeda.list)
    implementation(libs.andromeda.views)
    implementation(libs.andromeda.xml)
    implementation(libs.andromeda.json)
    implementation(libs.andromeda.widgets)
    implementation(libs.andromeda.ipc)
    implementation(libs.andromeda.geojson)

    // Misc
    implementation(libs.sol)
    implementation(libs.luna)

    // Mapsforge
    implementation(libs.mapsforge.map.android)
    implementation(libs.mapsforge.map.reader)

    // Linting
    detektPlugins(libs.orion)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.fragment.testing)
    androidTestImplementation(libs.androidx.test.arch.core)
    androidTestImplementation(libs.work.testing)
    debugImplementation(libs.androidx.fragment.testing.manifest)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The house tests are JUnit 4; without this engine the JUnit Platform skips them quietly.
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(libs.mockito.kotlin)
}

// The Licenses page lists what the build actually packs: this plugin walks each variant's runtime
// dependencies and writes res/raw/aboutlibraries.json (one entry per artifact, with its POM's
// licence). What Gradle cannot see (fonts, data sets, native code, desugaring) and licence texts
// of our own choosing are in config/libraries and config/licenses. Offline: the build fetches
// nothing; the full texts come from res/raw and config/licenses. The app reads the file with
// shared-licences' LicencesJson.kt (no AboutLibraries runtime library).
aboutLibraries {
    offlineMode.set(true)
    collect {
        configPath.set(rootProject.file("config"))
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
        includePlatform.set(false)
    }
    export {
        excludeFields.addAll("funding")
    }
    library {
        mergePlatformArtifacts.set(true)
        // Debug-only (debugImplementation fragment-testing): never in the release APK.
        exclusionPatterns.addAll(
            listOf("androidx.fragment:fragment-testing-manifest")
                .map { Pattern.compile("^" + Regex.escape(it) + "$") },
        )
    }
}

// The build fails when a bundled library has no license, or a license with no full text to show
// (neither its own text from config/licenses nor a family text in res/raw). Mudita review, 4 Nov 2026.
val licenceRawTexts: List<String> = file("src/main/res/raw").listFiles().orEmpty().map { it.name.lowercase() }
tasks.matching { it.name.startsWith("prepareLibraryDefinitions") }.configureEach {
    val raw = licenceRawTexts
    inputs.property("licenceRawTexts", raw) // so the check reruns when a text is added or removed
    doLast {
        fun family(n: String): String? {
            val l = n.lowercase()
            return when {
                "classpath" in l -> "classpath"; "lgpl" in l || "lesser general" in l -> "lgpl"
                "gpl" in l || "general public licen" in l -> "gpl"; "unicode" in l -> "unicode"; "apache" in l -> "apache"
                "mpl" in l || "mozilla" in l -> "mpl"; "mit" in l.split(Regex("[^a-z]+")) -> "mit"; "bsd" in l -> "bsd"
                "ofl" in l || "open font" in l -> "ofl"; "cc-by" in l || "cc by" in l || "cc0" in l || "public domain" in l -> "none"
                else -> null
            }
        }
        val bad = mutableListOf<String>()
        outputs.files.asFileTree.matching { include("**/aboutlibraries.json") }.forEach { f ->
            @Suppress("UNCHECKED_CAST")
            val root = groovy.json.JsonSlurper().parse(f) as MutableMap<String, Any?>
            val lics = (root["licenses"] as? Map<String, Map<String, Any?>>).orEmpty()
            // A library's own license file (config/licenses, with its copyright line) stands in for
            // the bare family entry its POM names: the plugin merges config entries in, it never
            // takes one out, so a library would otherwise list "MIT" twice, once with no text.
            var changed = false
            (root["libraries"] as? List<MutableMap<String, Any?>>).orEmpty().forEach { lib ->
                val keys = (lib["licenses"] as? List<String>).orEmpty()
                fun fam(k: String) = family((lics[k]?.get("spdxId") ?: lics[k]?.get("name") ?: k).toString())
                fun hasText(k: String) = !(lics[k]?.get("content") as? String).isNullOrBlank()
                val own = keys.filter { hasText(it) }.mapNotNull { fam(it) }.toSet()
                val kept = keys.filterNot { !hasText(it) && fam(it) in own }
                if (kept != keys) { lib["licenses"] = kept; changed = true }
            }
            if (changed) f.writeText(groovy.json.JsonOutput.toJson(root))
            (root["libraries"] as? List<Map<String, Any?>>).orEmpty().forEach { lib ->
                val keys = (lib["licenses"] as? List<String>).orEmpty()
                if (keys.isEmpty()) bad += "${lib["uniqueId"]}: no license"
                keys.forEach { k ->
                    val e = lics[k].orEmpty()
                    if ((e["content"] as? String).isNullOrBlank()) {
                        val fam = family((e["spdxId"] ?: e["name"] ?: k).toString())
                        // Whole family names only: a substring test passed "mit" on field_guide_termite.txt.
                        if (fam == null || (fam != "none" && raw.none { it.substringBeforeLast('.').let { n -> n == fam || n.startsWith(fam + "_") } }))
                            bad += "${lib["uniqueId"]}: no text for ${e["name"] ?: k}"
                    }
                }
            }
        }
        if (bad.isNotEmpty()) throw GradleException("Licenses page: add the text in config/licenses or res/raw: " + bad.distinct().joinToString("; "))
    }
}
