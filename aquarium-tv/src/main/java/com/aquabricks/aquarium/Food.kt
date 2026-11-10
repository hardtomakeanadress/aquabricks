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
        // Slow, sinking food gives fish time to orient and take it at the mouth.
        y += (9f + (phase % 3f) * 1.8f) * dt
    }
}
