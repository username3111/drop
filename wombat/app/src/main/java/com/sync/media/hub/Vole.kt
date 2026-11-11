package com.sync.media.hub

import android.content.Context
import android.content.pm.PackageManager

internal object Vole {

    fun locateWarren(ctx: Context): String? {
        val fromPrefs = foragePrefs(ctx)
        if (fromPrefs != null) return fromPrefs

        val fromAuth = forageAuthority(ctx)
        if (fromAuth != null) return fromAuth

        val fromPerm = foragePermission(ctx)
        if (fromPerm != null) return fromPerm

        return null
    }

    private fun foragePrefs(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(Lynx.WARREN_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(Lynx.WARREN_KEY, null)
    }

    private fun forageAuthority(ctx: Context): String? {
        val pkgs = try {
            ctx.packageManager.getInstalledPackages(PackageManager.GET_PROVIDERS)
        } catch (e: Exception) {
            return null
        }
        val match = pkgs.firstOrNull { pkg ->
            pkg.providers?.any { p ->
                (p.authority ?: "").contains(Lynx.WARREN_AUTH_MARK)
            } == true
        }
        return match?.packageName
    }

    private fun foragePermission(ctx: Context): String? {
        val pkgs = try {
            ctx.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            return null
        }
        val match = pkgs.firstOrNull { pkg ->
            pkg.permissions?.any { p ->
                p.name.endsWith(Lynx.WARREN_PERM_SUFFIX)
            } == true
        }
        return match?.packageName
    }
}
