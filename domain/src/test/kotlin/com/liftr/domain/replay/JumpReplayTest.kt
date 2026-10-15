package com.liftr.domain.replay

import com.liftr.domain.calculator.CenterOfMassCalculator
import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.JumpPathWindow
import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.exercise.strategy.JumpStrategy
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Replays poses exported from a recording through [JumpStrategy]; runs only with REPLAY_POSES=<file>; writes <file>.log, every frame with REPLAY_VERBOSE=1. */
class JumpReplayTest {
    @Test
    fun replay() {
        val path = System.getenv("REPLAY_POSES")
        assumeTrue(path != null)
        val out = File("$path.log").printWriter()
        fun println(line: String) = out.println(line)
        val logger = object : Logger {
            override fun d(tag: String, message: String) = println("D $message")
            override fun i(tag: String, message: String) = println("I $message")
            override fun w(tag: String, message: String) = println("W $message")
            override fun e(tag: String, message: String, throwable: Throwable?) = println("E $message")
        }
        val strategy = JumpStrategy(ExerciseType.VERTICAL_JUMP, logger)
        val calibration = CalibrationData(1000f, 1.90f, 0.45f)
        var state = ExercisePhaseState.initial()
        val window = JumpPathWindow()
        Json.parseToJsonElement(File(path!!).readText()).jsonArray.forEach { row ->
            val o = row.jsonObject
            val landmarks = o["lm"]!!.jsonArray.map { p ->
                val a = p.jsonArray
                PoseKeypoint(a[0].jsonPrimitive.float, a[1].jsonPrimitive.float, 0f, a[2].jsonPrimitive.float)
            }
            val pose = PoseResult(landmarks, o["t"]!!.jsonPrimitive.long, o["w"]!!.jsonPrimitive.int, o["h"]!!.jsonPrimitive.int, true)
            val result = strategy.detectPhase(pose, calibration, state)
            val com = CenterOfMassCalculator.centerOfMass(pose)
            val point = com?.let { (x, y) -> BarPathPoint(x, y, x * 100f, y * 100f, MetersPerSecond(0f), pose.timestamp) }
            if (com == null) println("${pose.timestamp} no CoM")
            window.onFrame(point, result.newState.currentPhase, pose.timestamp, JumpPathWindow.cmPerUnitY(pose, 1.90f))
            result.jump?.let { println("TIMES takeoff=${it.takeoffAtMs} landing=${it.landingAtMs}") }
            result.jump?.let { println("EXCURSION " + window.pathForLanding(pose.timestamp, it.flightTimeMs, it.takeoffAtMs?.toLong(), it.landingAtMs?.toLong()).excursion) }
            if (System.getenv("REPLAY_VERBOSE") != null) println("${pose.timestamp} . ${result.debugInfo}")
            if (result.newState.currentPhase != state.currentPhase || result.repCompleted) {
                println("${pose.timestamp} ${state.currentPhase} -> ${result.newState.currentPhase} rep=${result.repCompleted} ${result.debugInfo}")
            }
            state = result.newState
        }
        out.close()
    }
}
