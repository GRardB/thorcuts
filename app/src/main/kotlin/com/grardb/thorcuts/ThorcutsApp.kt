package com.grardb.thorcuts

import android.app.Application
import android.content.ComponentName
import android.content.Context
import com.grardb.thorcuts.platform.config.AndroidConfigFile
import com.grardb.thorcuts.platform.config.PrefsEnabledSwitch
import com.grardb.thorcuts.platform.coordinator.ThorcutsCoordinator
import com.grardb.thorcuts.platform.helper.HelperSupervisor
import com.grardb.thorcuts.platform.log.AndroidLogger
import com.grardb.thorcuts.platform.privilege.PserviceGateway
import com.grardb.thorcuts.platform.tasks.HomePackages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process: builds the one [ThorcutsCoordinator] with its real collaborators and holds it
 * for as long as the process lives (parent spec §7: the supervisor "must be owned above the
 * screen"). The Activity's ViewModel, [HelperService] and [BootReceiver] all reach it here.
 * Construction does no I/O on main: the store's load hops to its own thread, the switch is
 * one small preferences read, and nothing privileged runs until something asks.
 */
class ThorcutsApp : Application() {

    lateinit var coordinator: ThorcutsCoordinator
        private set

    /**
     * Where the screen's writes run: process-lifetime, on main, never cancelled — so a Save
     * survives the screen that made it closing before the store's hop (the editor's final
     * review, E5-3). Not the coordinator's privileged scope: a config write must not wait
     * behind a stop that can block for seconds (config spec), and the store hops to its own
     * thread anyway; this scope only carries the call and its outcome.
     */
    val writes: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // The keep-alive notification's channel, so its settings page exists before the
        // switch is ever turned on (two binder calls, idempotent).
        KeepAliveChannel.create(this)
        val gateway = PserviceGateway(this)
        coordinator = ThorcutsCoordinator(
            gateway = gateway,
            channel = { onMessage -> HelperSupervisor(this, gateway, onMessage) },
            file = AndroidConfigFile(this),
            switch = PrefsEnabledSwitch(this),
            // Hold = the foreground service exists; release = it is gone.
            keeper = { on -> if (on) HelperService.start(this) else HelperService.stop(this) },
            // Our own Activity as `am start -n` takes it: what OpenThorcuts launches.
            thorcutsMain = ComponentName(this, MainActivity::class.java).flattenToShortString(),
            // The launchers, resolved on every close so a launcher change is honoured.
            homePackages = { HomePackages.resolve(packageManager) },
            log = AndroidLogger("ThorcutsCoord"),
        )
    }

    companion object {
        fun of(context: Context): ThorcutsApp = context.applicationContext as ThorcutsApp
    }
}
