package com.aquabricks.aquarium

/**
 * Everything the tank needs to remember about a fish between launches.
 * The PNG itself lives next to fish.json in filesDir/fish.
 */
data class FishMeta(
    val id: String,
    val name: String,
    val creator: String,
    val facing: String, // "left" or "right" as scanned
    val addedAt: Long,
    val width: Int,
    val height: Int
)
