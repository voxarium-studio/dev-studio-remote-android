import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// protocol §13: an independent VS-78 acceptance report, not a user preference or a
// build -P boolean, arms original-document writes. No report means safe room viewing.
val gateInputs = rootProject.fileTree("app/src") { include("main/**", "test/**") }.files +
    rootProject.fileTree("editor-web") {
        include("src/**", "tests/**", "scripts/**", "package.json", "package-lock.json", "*config*.json", "vite.config.ts")
    }.files + file("build.gradle.kts")
fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
val gateDigest = MessageDigest.getInstance("SHA-256")
gateInputs.filter { it.isFile }.sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }.forEach {
    gateDigest.update(it.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
    gateDigest.update(0.toByte()); gateDigest.update(it.readBytes()); gateDigest.update(0.toByte())
}
val editorSourceSha256 = gateDigest.digest().joinToString("") { "%02x".format(it) }
val editorGateReport = rootProject.file("verification/editor-write-gate.json")
val editorWritesEnabled = if (!editorGateReport.exists()) false else {
    check(editorGateReport.length() <= 16_384) { "Editor acceptance report is too large" }
    val report = JsonSlurper().parseText(editorGateReport.readText()) as? Map<*, *> ?: error("Invalid editor acceptance report")
    val checks = listOf("schemaParity", "roomInterop", "noWriteOnOpen", "scribeAckOrdering", "recoveryConflict")
    check(report.keys == (checks + listOf("version", "sourceSha256", "evidence", "evidenceSha256")).toSet()) { "Invalid editor acceptance fields" }
    check(report["version"] == 1 && report["sourceSha256"] == editorSourceSha256 && checks.all { report[it] == true }) {
        "Editor acceptance is stale or incomplete; rerun VS-78, do not bypass the gate"
    }
    val evidence = report["evidence"] as? String ?: error("Missing editor acceptance evidence")
    check(evidence.startsWith("verification/") && evidence.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
        evidence.all { it.isLetterOrDigit() && it.code < 128 || it in "/._-" }) { "Invalid editor evidence path" }
    val evidenceFile = rootProject.file(evidence)
    check(evidenceFile.isFile && evidenceFile.canonicalPath.startsWith(rootProject.file("verification").canonicalPath + File.separator) &&
        sha256(evidenceFile.readBytes()) == report["evidenceSha256"]) { "Missing or changed editor acceptance evidence" }
    true
}
tasks.register("printEditorGateFingerprint") { doLast { println("editorSourceSha256=$editorSourceSha256") } }

android {
    namespace = "com.voxarium.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voxarium.remote"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    // protocol §13: local parity is necessary, NOT the device/Studio interop write gate.
    defaultConfig { buildConfigField("boolean", "EDITOR_WRITES_ENABLED", editorWritesEnabled.toString()) }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/editorAssets"))
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // 1.15.0 is the pinned stable WebKit line supporting this app's minSdk 23.
    implementation("androidx.webkit:webkit:1.15.0")
    // VS-73: API 21+ transport, pinned together with its TLS/mock test artifacts.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // Android supplies org.json at runtime; local JVM tests use the real implementation.
    testImplementation("org.json:json:20240303")
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

// Offline runtime only: no CDN, global installs or checked-in generated bundle. The existing
// npm lock and local node_modules must be provisioned with npm ci --ignore-scripts first.
val generateEditorAssets by tasks.registering {
    val web = rootProject.layout.projectDirectory.dir("editor-web")
    val work = providers.gradleProperty("editorBuildTemp").map { file(it) }
        .orElse(layout.buildDirectory.dir("editor-work").map { it.asFile })
    val assets = layout.buildDirectory.dir("generated/editorAssets")
    inputs.dir(web.dir("src")); inputs.dir(web.dir("tests")); inputs.dir(web.dir("scripts"))
    inputs.files(web.file("package.json"), web.file("package-lock.json"), web.file("vite.config.ts"))
    outputs.dir(assets)
    // Run gates on every invocation rather than claiming a cached parity check is fresh.
    outputs.upToDateWhen { false }
    doLast {
        val directory = work.get().apply { mkdirs() }
        val dist = directory.resolve("dist")
        val npm = if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd", "/c", "npm.cmd") else listOf("npm")
        fun runNpm(vararg args: String) {
            project.exec {
                workingDir(web.asFile)
                commandLine(npm + args)
                environment("EDITOR_DIST_DIR", dist.absolutePath)
                environment("EDITOR_CACHE_DIR", directory.resolve("vite-cache").absolutePath)
                environment("npm_config_cache", directory.resolve("npm-cache").absolutePath)
                environment("TEMP", directory.absolutePath)
                environment("TMP", directory.absolutePath)
            }
        }
        runNpm("run", "typecheck")
        runNpm("test") // includes exact schema-golden/provenance and Markdown parity corpus
        runNpm("run", "build")
        runNpm("run", "test:bundle")
        check(dist.resolve("room/index.html").isFile) { "Missing secure room runtime: editor-web build must emit room/index.html" }
        project.sync {
            from(dist.resolve("room")) { rename("index.html", "room.html") }
            from(web.file("NOTICE")); from(web.file("PROVENANCE.json"))
            from(web.dir("licenses")) { into("licenses") }
            into(assets.get().dir("editor"))
        }
    }
}
tasks.named("preBuild").configure { dependsOn(generateEditorAssets) }
// Packaging a write-capable APK also requires the native room/no-write/recovery gates.
// As with any build task, developers must not publish a package made with excluded checks.
tasks.matching { it.name == "packageDebug" || it.name == "packageRelease" }.configureEach {
    dependsOn(if (name == "packageDebug") "testDebugUnitTest" else "testReleaseUnitTest")
}
