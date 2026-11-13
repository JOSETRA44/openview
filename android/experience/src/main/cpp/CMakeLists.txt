# Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
cmake_minimum_required(VERSION 3.22)
project(openview_experience LANGUAGES CXX)
find_package(OpenXR REQUIRED CONFIG)
add_library(openview-experience SHARED experience.cpp)
target_compile_features(openview-experience PRIVATE cxx_std_17)
target_compile_definitions(openview-experience PRIVATE XR_USE_PLATFORM_ANDROID XR_USE_GRAPHICS_API_OPENGL_ES)
target_compile_options(openview-experience PRIVATE -Wall -Wextra)
target_link_libraries(openview-experience PRIVATE OpenXR::openxr_loader android log EGL GLESv3)
