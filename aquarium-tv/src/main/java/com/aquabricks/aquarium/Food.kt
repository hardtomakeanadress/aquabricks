package com.aquabricks.aquarium

import kotlin.random.Random

class Food(
    val x: Float,
    var y: Float,
    private val rng: Random
) {
    val phase = rng.nextFloat() * 6.283f
    var age = 0f
    var eaten = false

    fun update(dt: Float) {
        age += dt
        y += (15f + (phase % 3f) * 2f) * dt
    }
}
