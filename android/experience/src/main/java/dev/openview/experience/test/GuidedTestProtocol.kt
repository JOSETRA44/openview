// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
package dev.openview.experience.test

/**
 * The guided tracking test: what the user is asked to do, for how long, and
 * what the head should do meanwhile. Pure Kotlin, no Android types, so the
 * protocol is testable on the JVM and the PC analyser can rely on its ids.
 *
 * Positions use the recentred OpenXR LOCAL space the experience reports:
 * metres, -Z ahead of where the user faced when the test started, +X right,
 * +Y up. `expected` is the displacement the step should produce from its
 * start to its end; null means "any" (for example turning the head).
 */
enum class StepKind { STILL, MOVE, ROTATE }

data class Vec3(val x: Float, val y: Float, val z: Float)

data class TestStep(
    /** Stable id, written to the log; the analyser keys on it. */
    val id: String,
    val kind: StepKind,
    val seconds: Int,
    /** Short line shown big in the headset. */
    val title: String,
    /** What the voice says when the step begins. */
    val spoken: String,
    val expected: Vec3? = null,
)

object GuidedTestProtocol {
    val ZERO = Vec3(0f, 0f, 0f)

    /** Walking test: drift when still, scale and direction when walking, jumps when turning. */
    val WALK: List<TestStep> = listOf(
        TestStep("prepare", StepKind.STILL, 6, "Prepárate",
            "Prueba de seguimiento. Sostén el teléfono como un visor, mira al frente y quédate quieto."),
        TestStep("still_start", StepKind.STILL, 8, "Quieto",
            "Quieto, sin moverte.", ZERO),
        TestStep("forward", StepKind.MOVE, 6, "1 metro adelante",
            "Camina un metro hacia adelante, despacio.", Vec3(0f, 0f, -1f)),
        TestStep("still_forward", StepKind.STILL, 4, "Quieto", "Quieto.", ZERO),
        TestStep("back", StepKind.MOVE, 6, "1 metro atrás",
            "Ahora un metro hacia atrás, al punto de partida.", Vec3(0f, 0f, 1f)),
        TestStep("still_back", StepKind.STILL, 4, "Quieto", "Quieto.", ZERO),
        TestStep("right", StepKind.MOVE, 5, "Medio metro a la derecha",
            "Un paso de medio metro a la derecha.", Vec3(0.5f, 0f, 0f)),
        TestStep("still_right", StepKind.STILL, 4, "Quieto", "Quieto.", ZERO),
        TestStep("left", StepKind.MOVE, 5, "Medio metro a la izquierda",
            "Vuelve medio metro a la izquierda.", Vec3(-0.5f, 0f, 0f)),
        TestStep("still_left", StepKind.STILL, 4, "Quieto", "Quieto.", ZERO),
        TestStep("turn", StepKind.ROTATE, 8, "Gira la cabeza, sin caminar",
            "Sin caminar, gira la cabeza a la izquierda y a la derecha."),
        TestStep("still_turn", StepKind.STILL, 5, "Mira al frente, quieto",
            "Mira al frente y quédate quieto.", ZERO),
        TestStep("crouch", StepKind.MOVE, 6, "Agáchate y vuelve a subir",
            "Agáchate un poco y vuelve a subir.", ZERO),
        TestStep("still_end", StepKind.STILL, 5, "Quieto", "Quieto, último paso.", ZERO),
    )

    fun totalSeconds(steps: List<TestStep>): Int = steps.sumOf { it.seconds }

    /**
     * Which step is running [elapsedMs] after the start, and how many whole
     * seconds remain in it. Null once the protocol is over.
     */
    fun at(steps: List<TestStep>, elapsedMs: Long): Position? {
        if (elapsedMs < 0) return Position(0, steps.firstOrNull()?.seconds ?: return null)
        var start = 0L
        steps.forEachIndexed { index, step ->
            val end = start + step.seconds * 1000L
            if (elapsedMs < end) {
                val remainingMs = end - elapsedMs
                return Position(index, ((remainingMs + 999) / 1000).toInt())
            }
            start = end
        }
        return null
    }

    data class Position(val index: Int, val secondsLeft: Int)
}
