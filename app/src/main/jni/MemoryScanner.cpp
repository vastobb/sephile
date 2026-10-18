#include "MemoryScanner.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <sstream>

namespace sephile {

MemoryScanner::MemoryScanner() = default;
MemoryScanner::~MemoryScanner() { detach(); }

bool MemoryScanner::attach(pid_t pid) {
    pid_ = pid;
    return mem_.attach(pid);
}
void MemoryScanner::detach() {
    mem_.detach();
    pid_ = -1;
}
bool MemoryScanner::isAttached() const { return mem_.isAttached(); }

void MemoryScanner::clearCandidates() { candidates_.clear(); }

std::string MemoryScanner::bytesToHex(const uint8_t* data, size_t len) const {
    static const char* hex = "0123456789ABCDEF";
    std::string out;
    out.reserve(len * 2);
    for (size_t i = 0; i < len; ++i) {
        out.push_back(hex[(data[i] >> 4) & 0xF]);
        out.push_back(hex[data[i] & 0xF]);
    }
    return out;
}

bool MemoryScanner::readCandidateBytes(const ScanCandidate& c, std::vector<uint8_t>& out) const {
    size_t typeSize = 1;
    switch (c.type) {
        case ValueType::Byte:   typeSize = 1; break;
        case ValueType::Short:  typeSize = 2; break;
        case ValueType::Dword:
        case ValueType::Float:  typeSize = 4; break;
        case ValueType::Qword:
        case ValueType::Double: typeSize = 8; break;
        default: typeSize = 4; break;
    }
    out.resize(typeSize);
    return mem_.read(c.address, out.data(), typeSize) == typeSize;
}

bool MemoryScanner::matches(const std::vector<uint8_t>& curBytes, ValueType type,
                            const ScanOptions& opts, bool isFirst) const {
    if (curBytes.empty()) return false;

    switch (type) {
        case ValueType::Byte: {
            int8_t cur; std::memcpy(&cur, curBytes.data(), 1);
            int8_t target = static_cast<int8_t>(opts.targetValue);
            if (opts.searchType == SearchType::Exact) return cur == target;
            if (opts.searchType == SearchType::UnknownInitial) return true; // accept anything
            if (opts.searchType == SearchType::Fuzzy) return std::abs(cur - target) >= opts.fuzzyDelta;
            if (opts.searchType == SearchType::GreaterThan) return cur > target;
            if (opts.searchType == SearchType::LessThan) return cur < target;
            return false;
        }
        case ValueType::Short: {
            int16_t cur; std::memcpy(&cur, curBytes.data(), 2);
            int16_t target = static_cast<int16_t>(opts.targetValue);
            if (opts.searchType == SearchType::Exact) return cur == target;
            if (opts.searchType == SearchType::UnknownInitial) return true;
            if (opts.searchType == SearchType::Fuzzy) return std::abs(cur - target) >= opts.fuzzyDelta;
            if (opts.searchType == SearchType::GreaterThan) return cur > target;
            if (opts.searchType == SearchType::LessThan) return cur < target;
            return false;
        }
        case ValueType::Dword: {
            int32_t cur; std::memcpy(&cur, curBytes.data(), 4);
            int32_t target = static_cast<int32_t>(opts.targetValue);
            if (opts.searchType == SearchType::Exact) return cur == target;
            if (opts.searchType == SearchType::UnknownInitial) return true;
            if (opts.searchType == SearchType::Fuzzy) return std::abs(cur - target) >= opts.fuzzyDelta;
            if (opts.searchType == SearchType::GreaterThan) return cur > target;
            if (opts.searchType == SearchType::LessThan) return cur < target;
            return false;
        }
        case ValueType::Qword: {
            int64_t cur; std::memcpy(&cur, curBytes.data(), 8);
            int64_t target = opts.targetValue;
            if (opts.searchType == SearchType::Exact) return cur == target;
            if (opts.searchType == SearchType::UnknownInitial) return true;
            if (opts.searchType == SearchType::Fuzzy) return std::abs(cur - target) >= opts.fuzzyDelta;
            if (opts.searchType == SearchType::GreaterThan) return cur > target;
            if (opts.searchType == SearchType::LessThan) return cur < target;
            return false;
        }
        case ValueType::Float: {
            float cur; std::memcpy(&cur, curBytes.data(), 4);
            float target = static_cast<float>(opts.targetValueF);
            if (opts.searchType == SearchType::Exact) return cur == target;
            if (opts.searchType == SearchType::UnknownInitial) return true;
            if (opts.searchType == SearchType::Fuzzy) return std::fabs(cur - target) >= static_cast<float>(opts.fuzzyDelta);
            if (opts.searchType == SearchType::GreaterThan) return cur > target;
            if (opts.searchType == SearchType::LessThan) return cur < target;
            return false;
        }
        case ValueType::Double: {
            double cur; std::memcpy(&cur, curBytes.data(), 8);
            double target = opts.targetValueF;
            if (opts.searchType == SearchType::Exact) return cur == target;
            if (opts.searchType == SearchType::UnknownInitial) return true;
            if (opts.searchType == SearchType::Fuzzy) return std::fabs(cur - target) >= opts.fuzzyDelta;
            if (opts.searchType == SearchType::GreaterThan) return cur > target;
            if (opts.searchType == SearchType::LessThan) return cur < target;
            return false;
        }
        default:
            return false;
    }
}

int64_t MemoryScanner::scanRegion(const MemoryRegion& region, const ScanOptions& opts,
                                   bool isFirst, ScanProgress progress) {
    if (!region.readable) return 0;
    if (opts.onlyWritable && !region.writable) return 0;

    // Address filter
    uintptr_t start = region.start;
    uintptr_t end = region.end;
    if (opts.regionStart > start) start = opts.regionStart;
    if (opts.regionEnd < end) end = opts.regionEnd;
    if (end <= start) return 0;

    size_t typeSize = 1;
    switch (opts.type) {
        case ValueType::Byte: typeSize = 1; break;
        case ValueType::Short: typeSize = 2; break;
        case ValueType::Dword:
        case ValueType::Float: typeSize = 4; break;
        case ValueType::Qword:
        case ValueType::Double: typeSize = 8; break;
        default: typeSize = 4; break;
    }

    // Read region in chunks
    const size_t chunk = 64 * 1024;
    std::vector<uint8_t> buf(chunk);
    int64_t found = 0;
    int64_t scanned = 0;

    for (uintptr_t off = start; off + typeSize <= end; off += typeSize) {
        size_t toRead = typeSize;
        // Reuse a small read; for simplicity read typeSize bytes per candidate addr.
        // (Perf-conscious: refactor to chunked reads later.)
        std::vector<uint8_t> tb(typeSize);
        if (mem_.read(off, tb.data(), typeSize) != typeSize) continue;

        scanned++;
        if (matches(tb, opts.type, opts, isFirst)) {
            ScanCandidate c;
            c.address = off;
            c.region = region;
            c.type = opts.type;
            c.hexDump = bytesToHex(tb.data(), typeSize);
            candidates_.push_back(c);
            found++;
        }

        if (progress && (scanned % 4096 == 0)) {
            progress(scanned, found);
        }
    }

    if (progress) progress(scanned, found);
    return found;
}

int64_t MemoryScanner::firstScan(const ScanOptions& opts, ScanProgress progress) {
    candidates_.clear();
    pid_ = opts.targetPid;
    if (!mem_.isAttached() || mem_.pid() != pid_) {
        if (!mem_.attach(pid_)) return -1;
    }

    int64_t totalFound = 0;
    auto regions = mem_.enumerateRegions();
    for (const auto& r : regions) {
        totalFound += scanRegion(r, opts, true, progress);
    }
    return totalFound;
}

int64_t MemoryScanner::nextScan(const ScanOptions& opts, ScanProgress progress) {
    // Refine: re-read existing candidates and keep those that still match.
    std::vector<ScanCandidate> next;
    next.reserve(candidates_.size());
    int64_t scanned = 0;

    for (auto& c : candidates_) {
        std::vector<uint8_t> tb;
        if (!readCandidateBytes(c, tb)) continue;
        scanned++;
        if (matches(tb, c.type, opts, false)) {
            c.hexDump = bytesToHex(tb.data(), tb.size());
            next.push_back(std::move(c));
        }
        if (progress && (scanned % 256 == 0)) {
            progress(scanned, static_cast<int64_t>(next.size()));
        }
    }
    candidates_ = std::move(next);
    if (progress) progress(scanned, static_cast<int64_t>(candidates_.size()));
    return static_cast<int64_t>(candidates_.size());
}

bool MemoryScanner::editValue(uintptr_t address, ValueType type, int64_t intVal, double floatVal) {
    if (!mem_.isAttached()) return false;

    switch (type) {
        case ValueType::Byte: {
            int8_t v = static_cast<int8_t>(intVal);
            return mem_.writeT(address, v);
        }
        case ValueType::Short: {
            int16_t v = static_cast<int16_t>(intVal);
            return mem_.writeT(address, v);
        }
        case ValueType::Dword: {
            int32_t v = static_cast<int32_t>(intVal);
            return mem_.writeT(address, v);
        }
        case ValueType::Qword: {
            return mem_.writeT(address, intVal);
        }
        case ValueType::Float: {
            float v = static_cast<float>(floatVal);
            return mem_.writeT(address, v);
        }
        case ValueType::Double: {
            return mem_.writeT(address, floatVal);
        }
        default:
            return false;
    }
}

void MemoryScanner::freeze(uintptr_t address, bool enabled, const std::string& value) {
    for (auto& c : candidates_) {
        if (c.address == address) {
            c.frozen = enabled;
            c.freezeValue = value;
            break;
        }
    }
}

}  // namespace sephile
