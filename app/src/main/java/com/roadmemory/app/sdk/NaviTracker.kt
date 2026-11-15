package com.roadmemory.app.sdk

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.roadmemory.app.data.NaviSnapshot

/**
 * NaviHelper adapter.
 *  - route start / arrival  -> trip boundaries and place learning
 *  - TBT distance           -> driving load (stay silent before a turn)
 */
class NaviTracker(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onNaviChanged(snapshot: NaviSnapshot)
        fun onRouteStarted(destinationName: String?)
        fun onArrived(destinationName: String?, address: String?)
    }

    private val navi = NaviHelper(context.applicationContext)

    @Volatile
    var snapshot = NaviSnapshot()
        private set

    private fun update(block: (NaviSnapshot) -> NaviSnapshot) {
        snapshot = block(snapshot)
        listener.onNaviChanged(snapshot)
    }

    private val events = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            update { it.copy(routeActive = true, destinationName = info.name) }
            listener.onRouteStarted(info.name)
        }

        override fun onRouteEnded() = update { it.copy(routeActive = false, nextGuidanceMeters = null) }

        override fun onRouteCancelled() = update { it.copy(routeActive = false, nextGuidanceMeters = null) }

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            update { it.copy(routeActive = false, nextGuidanceMeters = null) }
            listener.onArrived(info.name, info.address)
        }

        override fun onTBTInfo(list: List<TBTInfo>) {
            val next = list.minByOrNull { it.distance }
            update { it.copy(nextGuidanceMeters = next?.distance, nextGuidanceText = next?.description) }
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            update { it.copy(routeActive = status == NavigationStatus.ROUTE_DRIVE) }
        }

        override fun onError(code: NaviErrorCode) {
            Log.w(TAG, "navi error: $code")
        }
    }

    fun start() {
        try {
            navi.initialize(
                {
                    navi.addListener(events)
                    runCatching { navi.getDestinationInfo() }
                    runCatching { navi.getTBTInfo() }
                },
                { t -> Log.w(TAG, "NaviHelper connect failed: ${t.message}") },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "NaviHelper unavailable: ${t.message}")
        }
    }

    fun stop() {
        runCatching { navi.removeListener(events) }
        runCatching { navi.release() }
    }

    /** Demo helper. */
    fun inject(block: (NaviSnapshot) -> NaviSnapshot) = update(block)

    companion object {
        private const val TAG = "RM/Navi"
    }
}
