package com.telefarm

import android.app.Application
import android.content.Context
import com.telefarm.core.AppGraph
import com.telefarm.core.td.TelefarmLog

/**
 * Application entry point.
 *
 * The object graph is created once per process and owns the TDLib client, the repositories and
 * the file caches. Everything is released in [onTerminate]; Android normally kills the process
 * instead, and TDLib closes its database safely on the next start because all state is stored
 * in its own directory.
 */
class TelefarmApp : Application() {

    /** Service graph shared by activities and view models. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
        TelefarmLog.d(TAG, "Application started")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        graph.onLowMemory()
    }

    override fun onTerminate() {
        graph.shutdown()
        super.onTerminate()
    }

    private companion object {
        const val TAG = "TelefarmApp"
    }
}

/** Graph of the running application. */
val Context.appGraph: AppGraph
    get() = (applicationContext as TelefarmApp).graph
