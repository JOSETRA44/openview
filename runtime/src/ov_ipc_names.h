// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  IPC endpoint names shared by the OpenView runtime library and service.
 *
 * These must differ from Monado's defaults so an upstream monado-service and
 * openview-service can be installed side by side without stealing each
 * other's clients. The pidfile guards the socket, so both always change together.
 */

#pragma once

#define OV_IPC_SOCKET_FILENAME "openview_comp_ipc"
#define OV_IPC_PID_FILENAME "openview.pid"
