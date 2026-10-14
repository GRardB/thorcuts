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

    override fun onCreate() {
        super.onCreate()
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
