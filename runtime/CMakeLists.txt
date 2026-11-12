# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0

# The service is a plain executable only off Android: there it is hosted by an
# Android Service inside the APK (Monado's ipc/android), built by Gradle.
if(NOT ANDROID)
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
