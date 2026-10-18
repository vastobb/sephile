package com.sephile.app.model

enum class ValueType(val id: Int, val label: String, val size: Int) {
    BYTE(0, "Byte", 1),
    SHORT(1, "Short (2 bytes)", 2),
    DWORD(2, "4 bytes", 4),
    QWORD(3, "8 bytes", 8),
    FLOAT(4, "Float", 4),
    DOUBLE(5, "Double", 8);

    companion object {
        fun fromId(id: Int): ValueType = values().firstOrNull { it.id == id } ?: DWORD
    }
}

enum class SearchType(val id: Int, val label: String) {
    EXACT(0, "Exact value"),
    UNKNOWN(1, "Unknown initial value"),
    FUZZY(2, "Value changed / fuzzy"),
    GREATER_THAN(3, "Greater than"),
    LESS_THAN(4, "Less than");

    companion object {
        fun fromId(id: Int): SearchType = values().firstOrNull { it.id == id } ?: EXACT
    }
}

data class ScanCandidate(
    val address: Long = 0L,
    val regionPath: String = "",
    val regionStart: Long = 0L,
    val regionEnd: Long = 0L,
    val type: ValueType = ValueType.DWORD,
    val hexDump: String = "",
    val frozen: Boolean = false,
    val freezeValue: String = ""
)

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val uid: Int = 0,
    val isSystem: Boolean = false
)
