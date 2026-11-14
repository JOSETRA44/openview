// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
package dev.openview.recorder

/** Fixed camera model shared by calibration and subsequent tracking captures. */
data class CaptureSettings(
    val exposureNs: Long = 5_000_000L,
    val iso: Int = 0, // Zero chooses gain once from converged AE, then disables AE.
    val focusDiopters: Float = 0f,
) {
    init {
        require(exposureNs in 100_000L..30_000_000L) { "Exposure must be 0.1–30 ms" }
        require(iso in 0..12800) { "ISO must be 0 (automatic selection) or 1–12800" }
        require(focusDiopters.isFinite() && focusDiopters >= 0f) { "Invalid focus distance" }
    }
}
