package com.sephile.app.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sephile.app.engine.Engine
import com.sephile.app.engine.libsFull
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

    /** Live log of tool calls the model is making — rendered as "thinking". */
    val trace: StateFlow<List<String>> = agent.trace

    private val history = JSONArray()
    private val notes = StringBuilder()   // standing asides injected via /btw

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _busy.value) return
        if (t.startsWith("/")) { handleCommand(t); return }

        _msgs.value = _msgs.value + ChatMsg(true, t)
        _busy.value = true
        agent.clearTrace()
        pushUser(t)
        runTurn()
    }

    private fun pushUser(t: String) {
        if (history.length() == 0) history.put(systemMsg())
        history.put(JSONObject().put("role", "user").put("content", t))
    }

    private fun runTurn() {
        viewModelScope.launch {
            val key = AiSettings.key(ctx); val base = AiSettings.base(ctx); val model = AiSettings.model(ctx)
            if (key.isBlank() || model.isBlank()) {
                _msgs.value = _msgs.value + ChatMsg(false, "Add your API key and pick a model in Settings first.")
                _busy.value = false; return@launch
            }
            if (!Engine.isUp()) Engine.start(ctx)
            val reply = try { agent.run(history, key, base, model) } catch (e: Throwable) { "Error: ${e.message}" }
            history.put(JSONObject().put("role", "assistant").put("content", reply))
            _msgs.value = _msgs.value + ChatMsg(false, reply)
            _busy.value = false
        }
    }

    // ── slash commands ─────────────────────────────────────────────────────
    private fun handleCommand(t: String) {
        val cmd = t.substringBefore(' ').lowercase()
        val arg = t.substringAfter(' ', "").trim()
        when (cmd) {
            "/btw" -> {
                if (arg.isEmpty()) { _msgs.value = _msgs.value + ChatMsg(false, usageBtw); return }
                notes.append("\n- ").append(arg)
                if (history.length() > 0) history.put(0, systemMsg())
                _msgs.value = _msgs.value + ChatMsg(false, "Noted. I'll keep this in mind: \"$arg\"")
            }
            "/clear" -> clear()
            "/status" -> {
                val pid = Engine.targetPid.value
                _msgs.value = _msgs.value + ChatMsg(false,
                    if (pid > 0) "Attached to Minecraft (pid $pid), engine ${Engine.state.value}."
                    else "Not attached. Engine ${Engine.state.value}.")
            }
            "/libs" -> {
                if (Engine.targetPid.value <= 0) { _msgs.value = _msgs.value + ChatMsg(false, "Not attached yet."); return }
                viewModelScope.launch {
                    val libs = Engine.libsFull()
                    _msgs.value = _msgs.value + ChatMsg(false,
                        if (libs.isEmpty()) "No libraries found."
                        else "${libs.size} libraries:\n" + libs.joinToString("\n"))
                }
            }
            "/help" -> _msgs.value = _msgs.value + ChatMsg(false, HELP)
            else -> _msgs.value = _msgs.value + ChatMsg(false, "Unknown command $cmd. Try /help.")
        }
    }

    fun clear() {
        _msgs.value = emptyList()
        agent.clearTrace()
        while (history.length() > 0) history.remove(history.length() - 1)
    }

    private fun systemMsg(): JSONObject {
        val body = BASE_SYSTEM + if (notes.isNotEmpty()) "\n\nStanding notes from the user:\n$notes" else ""
        return JSONObject().put("role", "system").put("content", body)
    }

    private companion object {
        const val usageBtw =
            "/btw <note> — add a standing note I keep in mind for the whole session.\n" +
            "e.g. /btw XP is stored as a float, health as float too."

        const val HELP =
            "Commands:\n" +
            "/btw <note> — add a standing note I keep in mind\n" +
            "/status — engine + attach state\n" +
            "/libs — list Minecraft's loaded native libraries\n" +
            "/clear — wipe chat\n" +
            "/help — this list\n\n" +
            "Or just ask me to find/edit a value (XP, health, coords, item count)."

        // Minecraft Bedrock–specific knowledge baked into the system prompt.
        const val BASE_SYSTEM =
            "You are the AI inside Sephile, a root Android memory editor running alongside Minecraft " +
            "Bedrock (com.mojang.minecraftpe). You drive a real memory scanner via tools: value scan, " +
            "refine, AOB signature scan, read/write/freeze memory, list loaded libraries, and resolve ELF symbols.\n\n" +

            "MINECRAFT BEDROCK FACTS (use these):\n" +
            "- Player HEALTH and MAX HEALTH are stored as FLOAT (often 20.0 for a full bar).\n" +
            "- EXPERIENCE (XP) level is stored as FLOAT.\n" +
            "- Coordinates (x, y, z) are stored as DOUBLE or FLOAT.\n" +
            "- Item stack COUNTS are stored as DWORD (int32).\n" +
            "- Saturation / sprint / movement values are FLOAT.\n" +
            "- Player state objects live on the HEAP (anonymous rw mappings), NOT inside read-only " +
            "libraries like libminecraftpe.so. Libraries hold CODE and read-only data.\n" +
            "- The main game library is libminecraftpe.so. You can list_libraries then find_symbols " +
            "with lib='minecraftpe' and a filter like 'Player', 'Health', 'Level' to find functions.\n\n" +

            "WORKFLOW to find any in-game value (XP, health, coordinates, item counts):\n" +
            "1. find_minecraft to attach.\n" +
            "2. Read the current value from the user (e.g. XP=64, health=20.0, stack=64).\n" +
            "3. scan with that exact value. Use type=float for health/XP/coords, dword for item counts.\n" +
            "4. If the scan returns MORE THAN ~1,000 candidates: call cand_regions to see where they " +
            "cluster, then cand_filter with region='heap' to drop library/stack noise. Then continue.\n" +
            "5. Ask the user to change the value in-game, then next_scan with the new value.\n" +
            "6. Repeat until few candidates; get_candidates to inspect; write_memory to set it.\n\n" +
            "NEVER dump thousands of raw addresses to the user. If a scan explodes, narrow with " +
            "cand_regions + cand_filter + changed/unknown next_scans first. Be concise, and always say " +
            "exactly what the user should do in-game next."
    }
}
