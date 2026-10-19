package com.sephile.app.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sephile.app.engine.Engine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ChatMsg(val mine: Boolean, val text: String)

/** Drives the AI chat: keeps history, hands each turn to the [Agent] tool loop. */
class ChatViewModel(appCtx: Context) : ViewModel() {

    private val ctx = appCtx.applicationContext
    private val agent = Agent(AiSettings.pkg(ctx))

    private val _msgs = MutableStateFlow<List<ChatMsg>>(emptyList())
    val msgs: StateFlow<List<ChatMsg>> = _msgs

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val history = JSONArray()

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _busy.value) return
        _msgs.value = _msgs.value + ChatMsg(true, t)
        _busy.value = true

        if (history.length() == 0)
            history.put(JSONObject().put("role", "system").put("content", SYSTEM))
        history.put(JSONObject().put("role", "user").put("content", t))

        viewModelScope.launch {
            val key = AiSettings.key(ctx); val base = AiSettings.base(ctx); val model = AiSettings.model(ctx)
            if (key.isBlank() || model.isBlank()) {
                _msgs.value = _msgs.value + ChatMsg(false, "Add your API key and pick a model in Settings first.")
                _busy.value = false; return@launch
            }
            // Make sure the engine is up before the agent starts calling tools.
            if (!Engine.isUp()) Engine.start(ctx)
            val reply = try { agent.run(history, key, base, model) } catch (e: Throwable) { "Error: ${e.message}" }
            history.put(JSONObject().put("role", "assistant").put("content", reply))
            _msgs.value = _msgs.value + ChatMsg(false, reply)
            _busy.value = false
        }
    }

    fun clear() {
        _msgs.value = emptyList()
        while (history.length() > 0) history.remove(history.length() - 1)
    }

    private companion object {
        const val SYSTEM =
            "You are the AI inside Sephile, a root Android memory editor running alongside Minecraft " +
            "Bedrock (com.mojang.minecraftpe). You control a real memory scanner via tools: value " +
            "scan, refine, AOB signature scan, read/write/freeze memory.\n\n" +
            "Always start a session with find_minecraft to attach.\n\n" +
            "To find any in-game value (XP, health, coordinates, item counts):\n" +
            "1. find_minecraft.\n" +
            "2. Read the current value from the user (e.g. XP=64, stack=64).\n" +
            "3. scan with exact value, type dword (use float if it looks like a float).\n" +
            "4. Ask the user to change the value in-game, then next_scan with the new value.\n" +
            "5. Repeat until few candidates; get_candidates to inspect; write_memory to set it.\n" +
            "Use pattern_scan for byte signatures and read_maps for layout. Be concise, always say " +
            "exactly what the user should do in-game next."
    }
}
