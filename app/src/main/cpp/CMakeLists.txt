cmake_minimum_required(VERSION 3.22.1)
project(clipme_llm LANGUAGES C CXX)

# Gradle's Debug build would compile llama.cpp without optimisation (very slow). Always optimise.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_STANDARD 17)

include(FetchContent)
if(NOT LLAMA_TAG)
  set(LLAMA_TAG b5000)
endif()
message(STATUS "ClipMe: using llama.cpp tag ${LLAMA_TAG}")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_TAG}
    GIT_SHALLOW    TRUE)
FetchContent_MakeAvailable(llama)

add_library(clipme_llm SHARED llm_jni.cpp)
target_link_libraries(clipme_llm PRIVATE llama log android)
