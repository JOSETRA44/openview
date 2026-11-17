# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0

# Head position from ARCore behind Monado's SLAM port. Android only; the Java
# half lives in the runtime app (dev.openview.runtime.tracking.ArCoreTracker).
add_library(openview_arcore STATIC src/arcore_tracker.c src/arcore_convention.h include/openview/arcore_tracker.h)
target_include_directories(openview_arcore PUBLIC include PRIVATE src)
target_link_libraries(openview_arcore PRIVATE aux_os aux_util aux_android xrt-interfaces)
