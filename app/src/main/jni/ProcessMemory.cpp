#include "ProcessMemory.h"

#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define TAG "SephileNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace sephile {

ProcessMemory::~ProcessMemory() {
    detach();
}

bool ProcessMemory::attach(pid_t pid) {
    if (attached_ && pid_ == pid) {
        return true;
    }
    if (attached_) {
        detach();
    }

    // PTRACE_ATTACH stops the target and makes us its tracer. Requires root
    // on modern Android (yama ptrace_scope + SELinux).
    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) != 0) {
        LOGE("PTRACE_ATTACH failed for pid %d (errno=%d). Root required.", pid, errno);
        return false;
    }

    int status = 0;
    if (waitpid(pid, &status, __WALL) < 0) {
        LOGE("waitpid after ATTACH failed for pid %d", pid);
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    pid_ = pid;
    attached_ = true;
    LOGI("Attached to pid %d", pid);
    return true;
}

void ProcessMemory::detach() {
    if (!attached_) return;
    ptrace(PTRACE_DETACH, pid_, nullptr, nullptr);
    attached_ = false;
    LOGI("Detached from pid %d", pid_);
    pid_ = -1;
}

std::vector<MemoryRegion> ProcessMemory::enumerateRegions() const {
    std::vector<MemoryRegion> regions;
    if (!attached_) return regions;

    std::ostringstream path;
    path << "/proc/" << pid_ << "/maps";
    std::ifstream maps(path.str());
    if (!maps.is_open()) {
        LOGE("Cannot open /proc/%d/maps", pid_);
        return regions;
    }

    std::string line;
    while (std::getline(maps, line)) {
        MemoryRegion r;
        unsigned long start, end, offset, major, minor;
        char perms[5] = {0};
        char pathname[4096] = {0};
        int inode = 0;

        int n = sscanf(line.c_str(), "%lx-%lx %4s %lx %x:%x %d %4095s",
                       &start, &end, perms, &offset, &major, &minor, &inode, pathname);
        if (n < 7) continue;

        r.start = static_cast<uintptr_t>(start);
        r.end = static_cast<uintptr_t>(end);
        r.size = static_cast<size_t>(end - start);
        r.offset = static_cast<uintptr_t>(offset);
        r.major = major;
        r.minor = minor;

        r.readable = (perms[0] == 'r');
        r.writable = (perms[1] == 'w');
        r.executable = (perms[2] == 'x');

        if (pathname[0] != '\0') {
            r.pathname = pathname;
        }

        // Only keep readable regions (scannable). Writable ones are editable.
        if (r.readable && r.size > 0) {
            regions.push_back(r);
        }
    }
    return regions;
}

long ProcessMemory::ptraceRead(pid_t pid, uintptr_t addr) {
    errno = 0;
    long val = ptrace(PTRACE_PEEKTEXT, pid, reinterpret_cast<void*>(addr), nullptr);
    return val;
}

bool ProcessMemory::ptraceWrite(pid_t pid, uintptr_t addr, long data) {
    errno = 0;
    if (ptrace(PTRACE_POKETEXT, pid, reinterpret_cast<void*>(addr), reinterpret_cast<void*>(data)) != 0) {
        return false;
    }
    return true;
}

size_t ProcessMemory::read(uintptr_t addr, void* buf, size_t len) const {
    if (!attached_) return 0;
    if (len == 0) return 0;

    size_t total = 0;
    uint8_t* dst = static_cast<uint8_t*>(buf);

    // ptrace reads/writes one word (long) at a time. Align to word boundary.
    const size_t word = sizeof(long);
    uintptr_t cur = addr;

    // Handle leading unaligned bytes
    size_t lead = cur % word;
    if (lead) {
        long chunk = ptraceRead(pid_, cur - lead);
        size_t copy = word - lead;
        if (copy > len) copy = len;
        std::memcpy(dst, reinterpret_cast<uint8_t*>(&chunk) + lead, copy);
        dst += copy;
        total += copy;
        cur += copy;
    }

    // Aligned word-sized chunks
    while (total + word <= len) {
        long chunk = ptraceRead(pid_, cur);
        std::memcpy(dst, &chunk, word);
        dst += word;
        total += word;
        cur += word;
    }

    // Trailing bytes
    if (total < len) {
        long chunk = ptraceRead(pid_, cur);
        size_t remaining = len - total;
        std::memcpy(dst, &chunk, remaining);
        total += remaining;
    }

    return total;
}

size_t ProcessMemory::write(uintptr_t addr, const void* buf, size_t len) {
    if (!attached_) return 0;
    if (len == 0) return 0;

    size_t total = 0;
    const uint8_t* src = static_cast<const uint8_t*>(buf);
    const size_t word = sizeof(long);
    uintptr_t cur = addr;

    // Leading unaligned bytes: read-modify-write the word
    size_t lead = cur % word;
    if (lead) {
        long chunk = ptraceRead(pid_, cur - lead);
        size_t copy = word - lead;
        if (copy > len) copy = len;
        std::memcpy(reinterpret_cast<uint8_t*>(&chunk) + lead, src, copy);
        if (!ptraceWrite(pid_, cur - lead, chunk)) return total;
        src += copy;
        total += copy;
        cur += copy;
    }

    // Aligned words
    while (total + word <= len) {
        long chunk;
        std::memcpy(&chunk, src, word);
        if (!ptraceWrite(pid_, cur, chunk)) return total;
        src += word;
        total += word;
        cur += word;
    }

    // Trailing bytes
    if (total < len) {
        long chunk = ptraceRead(pid_, cur);
        size_t remaining = len - total;
        std::memcpy(&chunk, src, remaining);
        if (!ptraceWrite(pid_, cur, chunk)) return total;
        total += remaining;
    }

    return total;
}

}  // namespace sephile
