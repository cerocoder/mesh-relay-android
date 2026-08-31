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

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext, BuildConfig.DEBUG)
    }
}
