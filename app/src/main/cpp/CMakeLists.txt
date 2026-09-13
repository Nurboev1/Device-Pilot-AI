cmake_minimum_required(VERSION 3.22.1)

project("devicepilot-jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization flags for ARM64
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -Wall -Wextra")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -Wall -Wextra")

# Disable unnecessary components and build static libraries for Android
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

# If llama.cpp is present in the source tree as a submodule or directory, include it
if (EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    add_subdirectory(llama.cpp)
    include_directories(llama.cpp/include llama.cpp/ggml/include)
    set(LLAMA_LIBS llama)
else()
    # Check for prebuilt libllama in jniLibs
    include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)
    set(LLAMA_LIBS "")
endif()

add_library(
    devicepilot-jni
    SHARED
    llama-jni.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    devicepilot-jni
    ${log-lib}
    ${LLAMA_LIBS}
)
