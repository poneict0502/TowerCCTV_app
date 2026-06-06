package com.pone.towerccctv.data

data class AlertRecord(
    val id: Long = 0,
    val dateTime: String,
    val weight: Float?,
    val weightAlert: Boolean
) {
    val alertType: String get() = if (weightAlert) "중량 초과" else "정상"
}
