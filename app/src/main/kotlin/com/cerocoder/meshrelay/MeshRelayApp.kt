package com.cerocoder.meshrelay

import android.app.Application

/**
 * Owns the one [AppContainer] for the life of the process.
 *
 * The connection, the statistics engine and everything they hold live here
 * rather than on the activity, so a rotation recreates the screens and nothing
 * else.
 */
class MeshRelayApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * The container, or `null` if [onCreate] has not run yet.
     *
     * The framework calls it before it attaches any activity or service, so in
     * practice this is never null where it is read - but the readers are
     * `attachBaseContext` overrides, and a crash there is a crash before the app
     * has a screen to report it on. Cheap insurance in the one place where an
     * exception costs the most.
     */
    val containerOrNull: AppContainer? get() = if (::container.isInitialized) container else null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext, BuildConfig.DEBUG)
    }
}
