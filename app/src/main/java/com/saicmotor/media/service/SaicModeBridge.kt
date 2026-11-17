package com.saicmotor.media.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.saicmotor.common.CommandParcelable
import com.saicmotor.common.CommandParcelable.Companion.DISABLE
import com.saicmotor.common.CommandParcelable.Companion.ENABLE

/**
 * Talks to the SAIC launcher's mode manager, which picks the audio source
 * (radio, media, CarPlay…) at start-up and when the source button is pressed.
 *
 *  - At start-up it restores the last source after 10 s, but for media only
 *    once the media app has reported that source type available
 *    (`UPDATE_SOURCE_AVAILABLE`); it retries every 3 s, five times, then
 *    switches to the radio.
 *  - To restore media it starts MediaService with a [CommandParcelable]
 *    (`SWITCH_SOURCE_ONLY_PLAY` or `_PAUSE`).
 *  - We play as car source type 6, which the launcher records as USB1, so USB1
 *    is the type we report available.
 */
object SaicModeBridge {

    private const val TAG = "CarMedia.Mode"

    /** The command in a startService intent from the launcher, if there is one. */
    fun commandFrom(intent: Intent?): CommandParcelable? {
        if (intent == null || !intent.hasExtra(CommandParcelable.COMMAND_KEY)) return null
        return try {
            intent.setExtrasClassLoader(CommandParcelable::class.java.classLoader)
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<CommandParcelable>(CommandParcelable.COMMAND_KEY)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the launcher's command", e)
            null
        }
    }

    /**
     * Tells the launcher that our music can play, so it restores us at
     * start-up instead of the radio.  (The launcher also names a
     * com.saicmotor.hardkey service, but that app isn't on this firmware.)
     */
    fun reportAvailable(context: Context) {
        val states = listOf(
            CommandParcelable.TYPE_USB1   to (CommandParcelable.SETTING_USB1   to ENABLE),
            CommandParcelable.TYPE_USB2   to (CommandParcelable.SETTING_USB2   to DISABLE),
            CommandParcelable.TYPE_BT     to (CommandParcelable.SETTING_BT     to DISABLE),
            CommandParcelable.TYPE_ONLINE to (CommandParcelable.SETTING_ONLINE to DISABLE)
        )
        for ((type, setting) in states) {
            val (key, state) = setting
            try {
                Settings.System.putInt(context.contentResolver, key, state)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't write $key", e)
            }
            val command = CommandParcelable(CommandParcelable.UPDATE_SOURCE_AVAILABLE, context.packageName, type, state)
            send(context, command, CommandParcelable.LAUNCHER_PACKAGE, CommandParcelable.LAUNCHER_SERVICE)
        }
        Log.i(TAG, "Reported music as available to the launcher")
    }

    private fun send(context: Context, command: CommandParcelable, pkg: String, service: String) {
        try {
            command.sendTo(context, pkg, service)
        } catch (e: Exception) {
            // Not on this car (or not allowed): nothing else to do.
            Log.w(TAG, "Couldn't reach $pkg: ${e.javaClass.simpleName}")
        }
    }
}
