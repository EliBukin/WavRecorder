package bukin.wavrecorder

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Whether the app as a whole is in the foreground: [ProcessLifecycleOwner]'s lifecycle. It moves
 * to ON_STOP shortly (about 700 ms) after the app's last activity stops -- Home, another app, the
 * screen turning off -- but not for a configuration change, which stops and recreates the activity
 * within that delay, and not for switching between the Record and Library pages, which stops no
 * activity at all. [lifecycleProvider] is replaceable so tests can drive it directly.
 */
internal object AppForeground {
    internal var lifecycleProvider: () -> Lifecycle = { ProcessLifecycleOwner.get().lifecycle }

    val lifecycle: Lifecycle get() = lifecycleProvider()
}
