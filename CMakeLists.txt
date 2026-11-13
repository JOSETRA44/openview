# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0

cmake_minimum_required(VERSION 3.22)
project(
	openview
	VERSION 0.1.0
	LANGUAGES C CXX
	)

include(GNUInstallDirs)

set(MONADO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/monado)
if(NOT EXISTS ${MONADO_ROOT}/CMakeLists.txt)
	message(FATAL_ERROR "Monado submodule missing, run: git submodule update --init")
endif()

include(${MONADO_ROOT}/cmake/MonadoSetOptions.cmake)
include(${MONADO_ROOT}/cmake/MonadoOpenXRRuntime.cmake)

# monado-cli is only needed for its dataset batch runner (tools/bench), not by the runtime.
option(OPENVIEW_BUILD_EVAL_TOOLS "Build the tools used to evaluate tracking on recorded datasets" OFF)
set(_openview_eval_options)
if(OPENVIEW_BUILD_EVAL_TOOLS)
	set(_openview_eval_options XRT_MODULE_MONADO_CLI)
endif()

# Everything not listed here is forced OFF, so this list is the complete
# definition of what OpenView ships. See docs/adr/0001-runtime-derivado-de-monado.md.
monado_set_options(
	ENABLE
		XRT_MODULE_AUX_RENDER
		XRT_MODULE_AUX_SHADERS
		XRT_MODULE_COMPOSITOR
		XRT_MODULE_COMPOSITOR_MAIN
		XRT_MODULE_COMPOSITOR_CLIENT
		XRT_MODULE_COMPOSITOR_MULTI
		XRT_MODULE_COMPOSITOR_UTIL
		XRT_MODULE_IPC
		XRT_MODULE_OPENXR_STATE_TRACKER
		XRT_BUILD_DRIVER_SIMULATED
		# drv_remote links aux_vive without declaring the dependency upstream.
		XRT_MODULE_AUX_VIVE
		${_openview_eval_options}
	KEEP
		# Auto-detected per platform; Monado's own DEPENDS rules gate them.
		XRT_FEATURE_SSE2
		XRT_FEATURE_COLOR_LOG
		XRT_FEATURE_DEBUG_GUI
		XRT_FEATURE_WINDOW_PEEK
		XRT_FEATURE_AHARDWARE_BUFFER
		XRT_FEATURE_SLAM
		XRT_BUILD_DRIVER_ANDROID
		XRT_BUILD_DRIVER_EUROC
		XRT_BUILD_DRIVER_QWERTY
		XRT_BUILD_DRIVER_REMOTE
		BUILD_TESTING
		# Monado's own tests link the mock compositor.
		XRT_MODULE_COMPOSITOR_MOCK
	KEEP_SET OPENXR_FEATURES DEPENDENCIES
	)

add_subdirectory(${MONADO_ROOT} monado)
add_subdirectory(runtime)
