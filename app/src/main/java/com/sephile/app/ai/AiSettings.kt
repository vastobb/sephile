package com.sephile.app.ai

import android.content.Context
import android.content.SharedPreferences

/** Persisted AI config: key, base URL, chosen model, target package. */
object AiSettings {
    private const val FILE = "sephile_ai"
    private fun p(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Strip all whitespace on read — a saved key may already carry a stray
     *  newline/space from paste that would break the HTTP header. */
    fun key(c: Context) = (p(c).getString("key", "") ?: "").filterNot { it.isWhitespace() }
    fun setKey(c: Context, v: String) { p(c).edit().putString("key", v.filterNot { it.isWhitespace() }).apply() }

    fun base(c: Context) = (p(c).getString("base", "https://api.openai.com") ?: "").trim()
    fun setBase(c: Context, v: String) { p(c).edit().putString("base", v.trim()).apply() }

    fun model(c: Context) = (p(c).getString("model", "") ?: "").trim()
    fun setModel(c: Context, v: String) { p(c).edit().putString("model", v.trim()).apply() }

    fun pkg(c: Context) = (p(c).getString("pkg", "com.mojang.minecraftpe") ?: "com.mojang.minecraftpe").trim()
    fun setPkg(c: Context, v: String) { p(c).edit().putString("pkg", v.trim()).apply() }
}
