cmake_minimum_required(VERSION 3.22.1)
project(pipevr_xr LANGUAGES CXX)

# PipeVR: immersive 360°/180° video through OpenXR (Quest, Pico, other OpenXR headsets)
find_package(OpenXR REQUIRED CONFIG)

add_library(pipevr_xr SHARED pipevr_xr.cpp)
target_compile_features(pipevr_xr PRIVATE cxx_std_17)
target_link_libraries(pipevr_xr
        OpenXR::openxr_loader
        android
        jnigraphics
        log
        EGL
        GLESv3)
