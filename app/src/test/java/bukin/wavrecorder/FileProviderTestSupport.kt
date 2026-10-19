package bukin.wavrecorder

import androidx.core.content.FileProvider

/**
 * [FileProvider] reads its roots (the app's files directories) once per process and caches them
 * in a static map. Robolectric gives every test its own temporary directories but keeps static
 * state across tests, so a later test's files would fall outside the roots an earlier test cached.
 * Tests that share files call [reset] first. (On a device the directories never change, so the
 * cache is correct there.)
 */
object FileProviderTestSupport {
    fun reset() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>
        synchronized(cache) { cache.clear() }
    }
}
