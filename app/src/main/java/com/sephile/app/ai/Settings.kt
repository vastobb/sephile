package com.sephile.app.ai

import android.content.Context
import android.content.SharedPreferences

/** Persisted API configuration (key, base URL, last-used model). */
object Settings {
    private const val FILE = "sephile_ai"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getKey(ctx: Context): String = prefs(ctx).getString("api_key", "") ?: ""
    fun setKey(ctx: Context, v: String) { prefs(ctx).edit().putString("api_key", v).apply() }

    fun getBase(ctx: Context): String = prefs(ctx).getString("base_url", "https://api.openai.com") ?: ""
    fun setBase(ctx: Context, v: String) { prefs(ctx).edit().putString("base_url", v).apply() }

    fun getModel(ctx: Context): String = prefs(ctx).getString("model", "") ?: ""
    fun setModel(ctx: Context, v: String) { prefs(ctx).edit().putString("model", v).apply() }

    fun getTarget(ctx: Context): String =
        prefs(ctx).getString("target_pkg", "com.mojang.minecraftpe") ?: "com.mojang.minecraftpe"
    fun setTarget(ctx: Context, v: String) { prefs(ctx).edit().putString("target_pkg", v).apply() }
}
