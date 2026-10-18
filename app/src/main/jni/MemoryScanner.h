#pragma once

#include "ProcessMemory.h"

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace sephile {

enum class ValueType : int8_t {
    Unknown = -1,
    Byte = 0,        // 1 byte, signed
    Short = 1,       // 2 bytes
    Dword = 2,       // 4 bytes (int32)
    Qword = 3,       // 8 bytes (int64)
    Float = 4,       // 4 bytes IEEE754
    Double = 5,      // 8 bytes IEEE754
};

enum class SearchType : int8_t {
    Exact = 0,
    UnknownInitial = 1,
    Fuzzy = 2,       // value changed (used with "next")
    GreaterThan = 3,
    LessThan = 4,
};

// A single candidate result from scanning
struct ScanCandidate {
    uintptr_t address = 0;
    MemoryRegion region;
    ValueType type = ValueType::Unknown;
    std::string hexDump;     // hex representation of current bytes
    bool frozen = false;
    std::string freezeValue; // value to repeatedly write when frozen
};

struct ScanOptions {
    pid_t targetPid = -1;
    ValueType type = ValueType::Dword;
    SearchType searchType = SearchType::Exact;
    int64_t targetValue = 0;          // exact/int target
    double targetValueF = 0.0;        // float/double target
    uintptr_t regionStart = 0;        // optional start address filter
    uintptr_t regionEnd = 0;          // optional end address filter
    bool onlyWritable = true;
    int64_t fuzzyDelta = 0;           // for fuzzy/changed searches
};

// Results callback: invoked as scanning progresses (streaming UI)
using ScanProgress = std::function<void(int64_t scanned, int64_t found)>;

class MemoryScanner {
public:
    MemoryScanner();
    ~MemoryScanner();

    // Attach to target process (root required). Returns true on success.
    bool attach(pid_t pid);
    void detach();
    bool isAttached() const;

    // First scan: populate candidate list from scratch.
    // Returns number of candidates found.
    int64_t firstScan(const ScanOptions& opts, ScanProgress progress = nullptr);

    // Next scan: refine existing candidates by re-reading their addresses.
    int64_t nextScan(const ScanOptions& opts, ScanProgress progress = nullptr);

    // Current candidates
    const std::vector<ScanCandidate>& candidates() const { return candidates_; }
    void clearCandidates();

    // Edit a candidate's value at its address
    bool editValue(uintptr_t address, ValueType type, int64_t intVal, double floatVal);

    // Freeze / unfreeze a candidate (freeze = keep writing value)
    void freeze(uintptr_t address, bool enabled, const std::string& value);

    // Get the attached ProcessMemory for direct ops
    ProcessMemory& memory() { return mem_; }

private:
    ProcessMemory mem_;
    std::vector<ScanCandidate> candidates_;
    pid_t pid_ = -1;

    bool readCandidateBytes(const ScanCandidate& c, std::vector<uint8_t>& out) const;
    bool matches(const std::vector<uint8_t>& curBytes, ValueType type,
                 const ScanOptions& opts, bool isFirst) const;
    int64_t scanRegion(const MemoryRegion& region, const ScanOptions& opts,
                       bool isFirst, ScanProgress progress);
    std::string bytesToHex(const uint8_t* data, size_t len) const;
};

}  // namespace sephile
