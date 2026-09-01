package com.cerocoder.meshrelay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.StampedPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The platform `LocationManager`, and deliberately not `play-services-location`:
 * that is a new dependency and a proprietary blob, and this application is
 * otherwise F-Droid-clean.
 *
 * Both providers are requested. GPS is the one that answers the question this
 * tool asks - where exactly was I standing - and NETWORK is what answers at all
 * indoors, in a car park, or in the first seconds after the screen comes on.
 * Whichever delivers last wins; the fix carries no accuracy figure because
 * nothing downstream branches on one.
 */
class AndroidPhoneLocationSource(context: Context) : PhoneLocationSource {

    private val manager = context.getSystemService(LocationManager::class.java)
    private val availability = LocationAvailability(context)

    private val _fix = MutableStateFlow<StampedPosition?>(null)
    override val fix: StateFlow<StampedPosition?> = _fix.asStateFlow()

    private var listening = false

    /**
     * An explicit object, not a SAM conversion.
     *
     * `LocationListener` gained default implementations of its other three
     * methods in API 30. This app's `minSdk` is 26, where they are still
     * abstract - and a lambda compiled against `compileSdk` 37 would produce a
     * class with no implementation of them and throw `AbstractMethodError` on an
     * old phone the moment the provider changed state. The three overrides are
     * deprecated and empty on purpose.
     */
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _fix.value = StampedPosition.fromDegrees(
                location.latitude,
                location.longitude,
                PositionOrigin.PHONE,
            )
        }

        @Deprecated("Required by LocationListener below API 30")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    // The permission is checked immediately above every call, by availability
    // .granted(); lint cannot see through the helper.
    //
    // @Synchronized, with stop() below, because two callers genuinely race:
    // AppContainer's settings collector calls start()/stop() from the application
    // scope's Dispatchers.Default, while MainActivity's permission callback calls
    // refreshLocationUpdates() - which also reaches start()/stop() - on the main
    // thread. `listening` being a plain var makes "if (listening) return; ...;
    // listening = true" a check-then-act, not one atomic read; @Volatile alone
    // would not close that window. Left open, stop() can read listening == false
    // while a start() is still in flight, return without calling removeUpdates,
    // and then have start() set listening = true - leaving the GNSS running after
    // the user switched the setting off.
    @Synchronized
    @SuppressLint("MissingPermission")
    override fun start() {
        if (listening) return
        val manager = manager ?: return
        if (!availability.granted()) return
        var requested = false
        for (provider in PROVIDERS) {
            // A phone with no GPS chip, or one whose provider the vendor has
            // removed, throws IllegalArgumentException from requestLocationUpdates
            // rather than returning. One missing provider must not cost the other.
            if (!manager.allProviders.contains(provider)) continue
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    MIN_TIME_MILLIS,
                    MIN_DISTANCE_METERS,
                    listener,
                    // The five-argument overload, so this can be called from the
                    // application scope's Dispatchers.Default rather than only from
                    // a thread that already has a Looper.
                    Looper.getMainLooper(),
                )
                requested = true
            }.onFailure { Log.w(TAG, "no location updates from $provider", it) }
        }
        listening = requested
    }

    @Synchronized
    override fun stop() {
        if (!listening) return
        manager?.removeUpdates(listener)
        listening = false
        // The last fix is deliberately kept. Turning the setting off does not
        // un-know where the phone was, and the engine's PositionMode.NODE is what
        // stops it being used - one rule, in one place, tested.
    }

    private companion object {
        const val TAG = "PhoneLocation"

        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /**
         * Ten seconds between deliveries.
         *
         * `minTime` is the only battery lever of the two: it is what lets the
         * platform power the GNSS down between fixes, at its own discretion. Ten
         * seconds keeps it warm, which is the price of the resolution this tool
         * needs - the question being asked in the field is *where exactly was I
         * standing when this relay read -92 dBm*, asked while walking a ridge to
         * decide where a repeater goes.
         */
        const val MIN_TIME_MILLIS = 10_000L

        /**
         * Ten metres of movement between deliveries.
         *
         * Not a battery lever at all: it filters *after* the GNSS has already
         * computed a fix, so it suppresses a callback, not the radio. It is a
         * de-duplication rule. At walking pace, roughly 1.4 m/s, ten metres is
         * about seven seconds, so measurements taken a few paces apart get
         * distinct pins. A coarser 100 m would stamp a whole hillside with one
         * coordinate and drop one pin for all of them, answering a different and
         * less useful question.
         *
         * Both gates apply to every delivery, so a phone on a table produces no
         * updates at all - which is right; it has not moved.
         */
        const val MIN_DISTANCE_METERS = 10f
    }
}
