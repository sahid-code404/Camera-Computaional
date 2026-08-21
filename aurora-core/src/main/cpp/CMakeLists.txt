cmake_minimum_required(VERSION 3.22.1)
project(aurora_core LANGUAGES CXX)

add_library(
    aurora_core
    SHARED
    aurora_jni.cpp
    hidden_camera_discovery.cpp
    auto_hidden_metadata.cpp
)

target_compile_features(aurora_core PRIVATE cxx_std_20)
target_include_directories(aurora_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

find_library(log-lib log)
find_library(android-lib android)
find_library(camera2ndk-lib camera2ndk)
find_library(mediandk-lib mediandk)

target_link_libraries(
    aurora_core
    PRIVATE
    ${log-lib}
    ${android-lib}
    ${camera2ndk-lib}
    ${mediandk-lib}
)
