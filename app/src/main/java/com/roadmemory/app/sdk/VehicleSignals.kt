package com.roadmemory.app.sdk

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import com.roadmemory.app.data.GearState
import com.roadmemory.app.data.TurnSignalState
import com.roadmemory.app.data.VehicleSnapshot

/**
 * Vehicle SDK adapter. Reads only — RoadMemory never controls the car.
 * Gear, turn signal, wiper, brake feed the driving load score;
 * seat occupancy tells who is on board; trip distance goes to the trip log.
 */
// Gear API is marked deprecated in Vehicle SDK 2.3.2 (in favour of the AOSP GEAR_SELECTION
// property) but is still the documented Pleos path for reading P/D on the emulator.
@Suppress("DEPRECATION")
class VehicleSignals(
    context: Context,
    private val onChange: (VehicleSnapshot) -> Unit,
) {
    private val vehicle = Vehicle(context.applicationContext)

    @Volatile
    var snapshot = VehicleSnapshot()
        private set

    private val frontWindshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val seatFront = SeatArea(SeatValue.ROW_1_RIGHT)
    private val seatRearLeft = SeatArea(SeatValue.ROW_2_LEFT)
    private val seatRearRight = SeatArea(SeatValue.ROW_2_RIGHT)

    private fun update(block: (VehicleSnapshot) -> VehicleSnapshot) {
        snapshot = block(snapshot)
        onChange(snapshot)
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = update { it.copy(gear = gear.toGearState()) }
        override fun onFailed(e: Exception) = log("gear", e)
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) =
            update { it.copy(turnSignal = value.toTurnState()) }
        override fun onFailed(e: Exception) = log("turnSignal", e)
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) =
            update { it.copy(wiperOn = state == WiperState.ON) }
        override fun onFailed(e: Exception) = log("wiper", e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) =
            update { it.copy(brakePressed = pressed == true) }
        override fun onFailed(e: Exception) = log("brake", e)
    }
    private val tripDistanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(km: Float?) = update { it.copy(tripDistanceKm = km) }
        override fun onFailed(e: Exception) = log("odometer", e)
    }
    private val seatFrontListener = seatListener("front") { s, v -> s.copy(passengerFront = v) }
    private val seatRearLeftListener = seatListener("rearLeft") { s, v -> s.copy(passengerRearLeft = v) }
    private val seatRearRightListener = seatListener("rearRight") { s, v -> s.copy(passengerRearRight = v) }

    private fun seatListener(name: String, apply: (VehicleSnapshot, Boolean) -> VehicleSnapshot) =
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupied: Boolean?) = update { apply(it, occupied == true) }
            override fun onFailed(e: Exception) = log("seat-$name", e)
        }

    fun start() {
        safe("initialize") { vehicle.initialize() }

        safe("gear") {
            vehicle.getGear().getCurrentGearSelectionState(
                { g -> update { it.copy(gear = g.toGearState()) } },
                { e -> log("gear", e) },
            )
            vehicle.getGear().registerGearSelection(gearListener)
        }
        safe("turnSignal") { vehicle.getTurnSignal().registerTurnSignalState(turnListener) }
        safe("wiper") { vehicle.getWiper().registerWiperState(frontWindshield, wiperListener) }
        safe("brake") { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        safe("odometer") { vehicle.getOdometer().registerTripCurrentDrivingDistance(tripDistanceListener) }
        safe("seats") {
            val seat = vehicle.getSeat()
            seat.registerSeatOccupancy(seatFront, seatFrontListener)
            seat.registerSeatOccupancy(seatRearLeft, seatRearLeftListener)
            seat.registerSeatOccupancy(seatRearRight, seatRearRightListener)
        }
    }

    fun stop() {
        safe("unregister") {
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getTurnSignal().unregisterTurnSignalState(turnListener)
            vehicle.getWiper().unregisterWiperState(frontWindshield, wiperListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            vehicle.getOdometer().unregisterTripCurrentDrivingDistance(tripDistanceListener)
            val seat = vehicle.getSeat()
            seat.unregisterSeatOccupancy(seatFront, seatFrontListener)
            seat.unregisterSeatOccupancy(seatRearLeft, seatRearLeftListener)
            seat.unregisterSeatOccupancy(seatRearRight, seatRearRightListener)
        }
        safe("release") { vehicle.release() }
    }

    /** Demo / emulator helper: inject a snapshot (used by the scenario simulator). */
    fun inject(block: (VehicleSnapshot) -> VehicleSnapshot) = update(block)

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "$what unavailable: ${t.message}")
        }
    }

    private fun log(what: String, e: Exception) {
        Log.w(TAG, "$what failed: ${e.message}")
    }

    companion object {
        private const val TAG = "RM/Vehicle"
    }
}

private fun VehicleGear?.toGearState(): GearState = when (this) {
    VehicleGear.GEAR_PARK -> GearState.PARK
    VehicleGear.GEAR_REVERSE -> GearState.REVERSE
    VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
    null, VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
    else -> GearState.DRIVE
}

private fun TurnSignalValue?.toTurnState(): TurnSignalState = when (this) {
    TurnSignalValue.LEFT -> TurnSignalState.LEFT
    TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
    else -> TurnSignalState.OFF
}
