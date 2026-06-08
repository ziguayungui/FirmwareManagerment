package com.example.firmwaremanagement.model

enum class Stage {
    IDLE,
    CHECK_PREPARE,
    DOWNLOADING,
    DOWNLOADED,
    PREPARING,
    APPLYING,
    REBOOT_PENDING,
    ERROR
}
