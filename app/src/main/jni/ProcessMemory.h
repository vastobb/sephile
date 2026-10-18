#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace sephile {

// A single readable/writable memory region parsed from /proc/<pid>/maps
struct MemoryRegion {
    uintptr_t start = 0;
    uintptr_t end = 0;
    size_t size = 0;
    bool readable = false;
    bool writable = false;
    bool executable = false;
    std::string pathname;
    uintptr_t offset = 0;
    int major = 0;
    int minor = 0;
};

// Low-level ptrace-based process memory reader/writer.
// Designed to run on Android (ARM64/ARM/x86_64) and requires root (ptrace ATTACH).
class ProcessMemory {
public:
    ProcessMemory() = default;
    ~ProcessMemory();

    // Attach to target process via ptrace (requires root). Call once before read/write.
    bool attach(pid_t pid);
    void detach();

    bool isAttached() const { return attached_; }
    pid_t pid() const { return pid_; }

    // Enumerate all readable memory regions of the target from /proc/<pid>/maps.
    std::vector<MemoryRegion> enumerateRegions() const;

    // Read raw bytes from target memory. Returns number of bytes actually read.
    size_t read(uintptr_t addr, void* buf, size_t len) const;

    // Write raw bytes to target memory. Returns number of bytes actually written.
    size_t write(uintptr_t addr, const void* buf, size_t len) const;

    // Convenience typed reads
    template <typename T>
    bool readT(uintptr_t addr, T& out) const {
        return read(addr, &out, sizeof(T)) == sizeof(T);
    }

    // Convenience typed writes
    template <typename T>
    bool writeT(uintptr_t addr, const T& val) const {
        return write(addr, &val, sizeof(T)) == sizeof(T);
    }

private:
    pid_t pid_ = -1;
    bool attached_ = false;

    // ptrace helpers
    static long ptraceRead(pid_t pid, uintptr_t addr);
    static bool ptraceWrite(pid_t pid, uintptr_t addr, long data);
};

}  // namespace sephile
