package com.sephile.app.ai

import android.content.Context
import android.content.SharedPreferences

/** Persisted AI config: key, base URL, chosen model, target package. */
object AiSettings {
    private const val FILE = "sephile_ai"
    private fun p(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun key(c: Context) = p(c).getString("key", "") ?: ""
    fun setKey(c: Context, v: String) { p(c).edit().putString("key", v).apply() }

    fun base(c: Context) = p(c).getString("base", "https://api.openai.com") ?: ""
    fun setBase(c: Context, v: String) { p(c).edit().putString("base", v).apply() }

    fun model(c: Context) = p(c).getString("model", "") ?: ""
    fun setModel(c: Context, v: String) { p(c).edit().putString("model", v).apply() }

    fun pkg(c: Context) = p(c).getString("pkg", "com.mojang.minecraftpe") ?: "com.mojang.minecraftpe"
    fun setPkg(c: Context, v: String) { p(c).edit().putString("pkg", v).apply() }
}
