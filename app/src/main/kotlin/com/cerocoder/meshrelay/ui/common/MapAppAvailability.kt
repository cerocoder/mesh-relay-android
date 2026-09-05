package com.cerocoder.meshrelay.ui.common

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri

/**
 * Whether a `geo:` URI - what [MapLinks.geoUri] builds - has anywhere to go on
 * this phone, and if there is more than one candidate, whether the platform
 * already has a default it would use without asking.
 *
 * [None] and [Several] are both real answers, not a fallback for something
 * that failed: a phone with no map application installed must not be told it
 * has one, and a phone with two and no default must not be told a specific
 * name it did not actually choose - [PackageManager.resolveActivity] would
 * hand back the system's own disambiguation activity in that case, and
 * treating that as an application would be a guess dressed as a fact.
 */
sealed interface MapAppState {
    data object None : MapAppState
    data class One(val name: String) : MapAppState
    data object Several : MapAppState
}

/**
 * Resolves [MapAppState] for the current device.
 *
 * Needs `AndroidManifest.xml`'s `<queries>` block for the `geo:` scheme: from
 * API 30, package visibility hides every other app's activities from
 * [PackageManager.queryIntentActivities] and [PackageManager.resolveActivity]
 * unless the caller declares an interest there, even with a map application
 * installed on the phone.
 */
object MapAppAvailability {

    /**
     * `0,0` rather than a real coordinate: the question here is only "does
     * anything handle the `geo:` scheme", and every activity that registers
     * for one handles all of them alike.
     */
    private const val PROBE_URI = "geo:0,0"

    /**
     * Queries every activity that can open [PROBE_URI]. `MATCH_DEFAULT_ONLY`
     * on both calls below matches what `startActivity` itself would consider:
     * an implicit intent is treated as if it declared `CATEGORY_DEFAULT`, so an
     * activity that does not declare that category in its filter is not a
     * candidate in practice either.
     *
     * With no handlers, [MapAppState.None]. With exactly one, that one is
     * unambiguously the answer regardless of any default - there is nothing
     * else it could be - so it is reported as [MapAppState.One] immediately,
     * without a second call.
     *
     * With several, [PackageManager.resolveActivity] is asked the same
     * question a real tap would ask, and its answer is checked against the
     * queried list: a match names one of the actual handlers, meaning a user
     * default is genuinely set, so that one is [MapAppState.One]. No match
     * means resolution either failed or returned the system's own resolver
     * activity - which is not an application this phone chose - so that is
     * reported as [MapAppState.Several].
     */
    @Suppress("DEPRECATION") // The typed-flags overloads are API 33+; minSdk here is 26.
    fun of(packageManager: PackageManager): MapAppState {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PROBE_URI))
        val handlers = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

        if (handlers.isEmpty()) return MapAppState.None
        if (handlers.size == 1) return MapAppState.One(handlers[0].label(packageManager))

        val default = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.takeIf { candidate -> handlers.any { it.isSameActivityAs(candidate) } }
        return default?.let { MapAppState.One(it.label(packageManager)) } ?: MapAppState.Several
    }

    private fun ResolveInfo.isSameActivityAs(other: ResolveInfo): Boolean =
        activityInfo.packageName == other.activityInfo.packageName && activityInfo.name == other.activityInfo.name

    private fun ResolveInfo.label(packageManager: PackageManager): String =
        loadLabel(packageManager).toString()
}
