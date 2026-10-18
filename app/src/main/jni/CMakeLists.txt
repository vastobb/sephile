cmake_minimum_required(VERSION 3.22.1)

project("sephile-native")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Optimize for size & speed on Android
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -funroll-loops")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")

# Locate Android NDK libraries
find_library(log-lib log)
find_library(android-lib android)

include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# Native shared library: libsephile_jni.so
add_library(sephile_jni SHARED
    ProcessMemory.cpp
    MemoryScanner.cpp
    native_bridge.cpp
)

target_link_libraries(sephile_jni
    ${log-lib}
    ${android-lib}
    c++_shared
)

# Set output name & visibility
set_target_properties(sephile_jni PROPERTIES
    PREFIX "lib"
    POSITION_INDEPENDENT_CODE ON
)
