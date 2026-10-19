package com.sephile.app.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Msg(val fromUser: Boolean, val text: String)

/**
 * Drives the AI chat: keeps the message history, hands each user turn to the
 * [Agent] which runs the tool loop against the memory engine.
 */
class ChatViewModel(
    private val memory: MemoryRepository,
    processes: ProcessRepository,
    private val appCtx: Context
) : ViewModel() {

    private val agent = Agent(memory, processes, Settings.getTarget(appCtx))

    private val _msgs = MutableStateFlow<List<Msg>>(emptyList())
    val msgs: StateFlow<List<Msg>> = _msgs

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val history = JSONArray()

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _busy.value) return
        _msgs.value = _msgs.value + Msg(true, t)
        _busy.value = true

        // First user message seeds a system prompt describing the tools' purpose.
        if (history.length() == 0) {
            history.put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
        }
        history.put(JSONObject().apply {
            put("role", "user")
            put("content", t)
        })

        viewModelScope.launch {
            val key = Settings.getKey(appCtx)
            val base = Settings.getBase(appCtx)
            val model = Settings.getModel(appCtx)
            if (key.isBlank() || model.isBlank()) {
                _msgs.value = _msgs.value + Msg(false,
                    "Set your API key and pick a model in Settings first.")
                _busy.value = false
                return@launch
            }
            val reply = try {
                agent.run(history, key, base, model)
            } catch (e: Throwable) {
                "Error: ${e.message}"
            }
            history.put(JSONObject().apply {
                put("role", "assistant")
                put("content", reply)
            })
            _msgs.value = _msgs.value + Msg(false, reply)
            _busy.value = false
        }
    }

    fun clearChat() {
        _msgs.value = emptyList()
        // Reset history but keep the system prompt on next send.
        while (history.length() > 0) history.remove(history.length() - 1)
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are Sephile, an AI memory-editing assistant embedded in a root Android app " +
            "running alongside Minecraft Bedrock (com.mojang.minecraftpe). You control a real " +
            "memory scanner via tools: value scan, refine (next_scan), AOB signature scan, " +
            "read/write memory, and freeze. Use them to locate and edit in-game values.\n\n" +
            "How to find a value (the standard workflow):\n" +
            "1. attach to Minecraft's pid.\n" +
            "2. Note the current in-game value (e.g. XP=64, health=20, a stack count).\n" +
            "3. scan with that exact value (type dword unless you have reason to think float).\n" +
            "4. Have the user change the value in-game, then next_scan with the new value.\n" +
            "5. Repeat until few candidates remain; get_candidates to inspect; write_memory to set it.\n" +
            "You can also pattern_scan with a byte signature (like '48 89 5C 24 ?? 57') to find code/\n" +
            "structures, and read_maps to see memory layout. Be concise and always tell the user " +
            "what in-game action to take next (e.g. 'drop the stack to 63, then tell me')."
    }
}
