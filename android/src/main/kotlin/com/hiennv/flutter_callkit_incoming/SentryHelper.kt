package com.hiennv.flutter_callkit_incoming

import android.util.Log

/**
 * Helper to send log messages to both Android Logcat AND Sentry breadcrumbs.
 *
 * Uses reflection to call Sentry APIs so the plugin compiles with or without
 * the Sentry SDK on the classpath.  At runtime the host app's Sentry instance
 * is reused — no extra initialisation is needed.
 */
object SentryHelper {

    private const val TAG = "CallkitSentry"

    // Cached reflection references (resolved once, reused)
    private var sentryClass: Class<*>? = null
    private var breadcrumbClass: Class<*>? = null
    private var sentryLevelClass: Class<*>? = null
    private var sentryAvailable: Boolean? = null

    /** Severity levels that map to SentryLevel enum constants. */
    enum class Level { DEBUG, INFO, WARNING, ERROR }

    /**
     * Log [message] under [tag] to Logcat **and** as a Sentry breadcrumb.
     *
     * @param tag      Logcat tag (also used as breadcrumb category)
     * @param message  The log line
     * @param level    Severity
     * @param data     Optional key-value pairs added to the breadcrumb
     */
    @JvmStatic
    fun log(
        tag: String,
        message: String,
        level: Level = Level.INFO,
        data: Map<String, Any>? = null
    ) {
        // Always log to Logcat
        when (level) {
            Level.DEBUG   -> Log.d(tag, message)
            Level.INFO    -> Log.i(tag, message)
            Level.WARNING -> Log.w(tag, message)
            Level.ERROR   -> Log.e(tag, message)
        }

        // Send breadcrumb via Sentry (reflection — safe if SDK absent)
        try {
            if (sentryAvailable == false) return
            ensureReflection()

            val breadcrumb = breadcrumbClass!!.getDeclaredConstructor().newInstance()

            // breadcrumb.message = message
            breadcrumbClass!!.getMethod("setMessage", String::class.java)
                .invoke(breadcrumb, message)

            // breadcrumb.category = "callkit.$tag"
            breadcrumbClass!!.getMethod("setCategory", String::class.java)
                .invoke(breadcrumb, "callkit.$tag")

            // breadcrumb.level = SentryLevel.<LEVEL>
            val sentryLevel = when (level) {
                Level.DEBUG   -> sentryLevelClass!!.getField("DEBUG").get(null)
                Level.INFO    -> sentryLevelClass!!.getField("INFO").get(null)
                Level.WARNING -> sentryLevelClass!!.getField("WARNING").get(null)
                Level.ERROR   -> sentryLevelClass!!.getField("ERROR").get(null)
            }
            breadcrumbClass!!.getMethod("setLevel", sentryLevelClass).invoke(breadcrumb, sentryLevel)

            // breadcrumb.setData(key, value) for each entry
            data?.forEach { (key, value) ->
                breadcrumbClass!!.getMethod("setData", String::class.java, Any::class.java)
                    .invoke(breadcrumb, key, value)
            }

            // Sentry.addBreadcrumb(breadcrumb)
            sentryClass!!.getMethod("addBreadcrumb", breadcrumbClass).invoke(null, breadcrumb)

        } catch (e: ClassNotFoundException) {
            sentryAvailable = false
            // Sentry SDK not on classpath — degrade silently
        } catch (e: Exception) {
            // Any other reflection issue — don't crash the call flow
            Log.w(TAG, "Sentry breadcrumb failed: ${e.message}")
        }
    }

    /** Resolve Sentry classes once via reflection. */
    private fun ensureReflection() {
        if (sentryClass == null) {
            sentryClass      = Class.forName("io.sentry.Sentry")
            breadcrumbClass  = Class.forName("io.sentry.Breadcrumb")
            sentryLevelClass = Class.forName("io.sentry.SentryLevel")
            sentryAvailable  = true
        }
    }
}
