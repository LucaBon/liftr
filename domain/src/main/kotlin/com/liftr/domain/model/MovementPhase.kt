package com.liftr.domain.model

enum class MovementPhase {
    IDLE,
    ECCENTRIC,    // Lowering
    BOTTOM,       // Transition
    CONCENTRIC,   // Lifting
    TOP           // Lockout
}
