# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0

if(ANDROID)
	# On Android the service is a library loaded by Monado's Java IPC module
	# (the serviceLibName Gradle property) inside an Android Service. The JNI
	# entry points are Monado's, so its source file is compiled as is.
	add_library(
		openview-service MODULE ${MONADO_ROOT}/src/xrt/targets/service-lib/service_target.cpp
		)
	target_link_libraries(
		openview-service
		PRIVATE
			aux_util
			st_prober
			ipc_server
			comp_main
			target_lists
			target_instance
			xrt-external-jni-wrap
		)
else()
	add_executable(openview-service src/service_main.c)
	target_link_libraries(
		openview-service
		PRIVATE
			aux_util
			aux_util_debug_gui
			st_prober
			ipc_server
			target_lists
			target_instance
		)
	install(TARGETS openview-service RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
endif()

monado_add_openxr_runtime(
	openxr_openview
	SOURCES src/target.c
	LINK_LIBRARIES ipc_client
	INSTALL
	)

if(ANDROID)
	# The loader dlcloses the runtime at xrDestroyInstance, but C++ thread-local
	# destructors can still run when the caller's Java/native render thread exits.
	# Keep their code mapped until process exit (observed SIGSEGV in
	# __cxa_thread_finalize on Infinix X6885). Sessions/IPC still close normally.
	target_link_options(openxr_openview PRIVATE "-Wl,-z,nodelete")
endif()
