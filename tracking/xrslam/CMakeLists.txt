# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
cmake_minimum_required(VERSION 3.16)
project(openview_xrslam_vit LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(Threads REQUIRED)
set(VIT_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/monado/src/external/vit_includes" CACHE PATH "VIT headers")
option(OPENVIEW_XRSLAM_BACKEND "Build real backend (requires upstream xrslam target)" OFF)

add_executable(vit-contract-test vit_adapter.cpp tests/contract.cpp)
target_include_directories(vit-contract-test PRIVATE "${VIT_INCLUDE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(vit-contract-test PRIVATE Threads::Threads)
enable_testing()
add_test(NAME vit-contract COMMAND vit-contract-test)

if(OPENVIEW_XRSLAM_BACKEND)
  if(NOT TARGET xrslam)
    message(FATAL_ERROR "Add this directory after pinned upstream xrslam; see README")
  endif()
  add_library(openview-xrslam-vit SHARED vit_adapter.cpp xrslam_backend.cpp)
  target_include_directories(openview-xrslam-vit PUBLIC "${VIT_INCLUDE_DIR}")
  target_link_libraries(openview-xrslam-vit PRIVATE xrslam depends::opencv Threads::Threads)
  # Upstream owns process-static state and has an incomplete Destroy function.
  # This experimental library must not unload while those objects can survive.
  if(UNIX AND NOT APPLE)
    target_link_options(openview-xrslam-vit PRIVATE "-Wl,-z,nodelete")
  endif()
  add_executable(vit-replay tests/replay.cpp)
  target_link_libraries(vit-replay PRIVATE openview-xrslam-vit depends::opencv)
endif()
