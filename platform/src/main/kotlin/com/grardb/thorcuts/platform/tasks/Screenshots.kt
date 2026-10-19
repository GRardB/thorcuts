package com.grardb.thorcuts.platform.tasks

import java.io.File

/**
 * The bottom screenshot's Android boundary (M7 spec §6): where the root script leaves the
 * frame, how it reaches the gallery, and how the user hears about it. `TaskActions` takes
 * one the way it takes `homePackages` and `launchComponent` — so the whole bottom path is
 * host-tested against `FakeScreenshots`, and only [GalleryScreenshots] touches MediaStore,
 * Toast and the shutter sound. Blocking where it blocks ([save]); call on
 * [com.grardb.thorcuts.platform.privilege.Privilege.dispatcher] like the rest of TaskActions.
 */
interface Screenshots {

    /** Where the root script leaves the bottom screen's PNG. Deleted by [save] in every case. */
    val frame: File

    /** File [frame] into the gallery's Screenshots album. One line: `OK saved
     *  Pictures/Screenshots/<name>`, `ERR frame: …` (not a PNG, unreadable) or `ERR gallery: …`. */
    fun save(): String

    /** The bottom path's outcome, for the user: a toast with the line's meaning, and the
     *  shutter click when it is an `OK`. Never for the top path — the system's popup is that
     *  feedback. Must not throw. */
    fun notify(line: String)
}
