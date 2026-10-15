package com.pwnotes.agent.data

import android.content.Context
import android.content.SharedPreferences

class AppWhitelistManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("allowed_apps_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        val DEFAULT_WHITELIST = setOf("com.penpencil.physicswalla", "xyz.penpencil.physicswala")
    }

    fun getAllowedPackages(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_PACKAGES, DEFAULT_WHITELIST) ?: DEFAULT_WHITELIST
    }

    fun addPackage(packageName: String) {
        val current = getAllowedPackages().toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, current).apply()
    }

    fun removePackage(packageName: String) {
        val current = getAllowedPackages().toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, current).apply()
    }

    fun isAppAllowed(packageName: String?): Boolean {
        if (packageName == null) return false
        val allowed = getAllowedPackages()
        return allowed.any { allowedPkg -> packageName.startsWith(allowedPkg, ignoreCase = true) }
    }
}
