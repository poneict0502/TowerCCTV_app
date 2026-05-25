package com.pone.towerccctv.data

data class AlertRecord(
    val id: Long = 0,
    val dateTime: String,
    val weight: Float?,
    val windSpeed: Float?,
    val weightAlert: Boolean,
    val windAlert: Boolean
) {
    val alertType: String get() = when {
        weightAlert && windAlert -> "중량+풍속 초과"
        weightAlert -> "중량 초과"
        windAlert   -> "풍속 초과"
        else        -> "정상"
    }
}
