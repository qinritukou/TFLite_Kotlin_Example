package com.orangeman.fortunetellerapp.env

import android.annotation.SuppressLint
import android.util.Log
import java.util.*


/** Wrapper for the platform log function, allows convenient message prefixing and log disabling.  */
class Logger @JvmOverloads constructor(
    private val tag: String = DEFAULT_TAG,
    messagePrefix: String? = null
) {
    companion object {
        private const val DEFAULT_TAG = "tensorflow"
        private const val DEFAULT_MIN_LOG_LEVEL = Log.DEBUG

        // Classes to be ignored when examining the stack trace
        private var IGNORED_CLASS_NAMES: MutableSet<String?>? =
            null// We're only interested in the simple name of the class, not the complete package.// Get the current callstack so we can pull the class of the caller off of it.

        /**
         * Return caller's simple name.
         *
         *
         * Android getStackTrace() returns an array that looks like this: stackTrace[0]:
         * dalvik.system.VMStack stackTrace[1]: java.lang.Thread stackTrace[2]:
         * com.google.android.apps.unveil.env.UnveilLogger stackTrace[3]:
         * com.google.android.apps.unveil.BaseApplication
         *
         *
         * This function returns the simple version of the first non-filtered name.
         *
         * @return caller's simple name
         */
        private val callerSimpleName: String
            private get() {
                // Get the current callstack so we can pull the class of the caller off of it.
                val stackTrace =
                    Thread.currentThread().stackTrace
                for (elem in stackTrace) {
                    val className = elem.className
                    if (!IGNORED_CLASS_NAMES!!.contains(className)) {
                        // We're only interested in the simple name of the class, not the complete package.
                        val classParts =
                            className.split("\\.".toRegex()).toTypedArray()
                        return classParts[classParts.size - 1]
                    }
                }
                return Logger::class.java.simpleName
            }

        init {
            IGNORED_CLASS_NAMES = hashSetOf(
                "dalvik.system.VMStack",
                "java.lang.Thread",
                "${Logger::class.java.canonicalName}"
            )
        }
    }

    private val messagePrefix: String
    private var minLogLevel = DEFAULT_MIN_LOG_LEVEL

    /**
     * Creates a Logger using the class name as the message prefix.
     *
     * @param clazz the simple name of this class is used as the message prefix.
     */
    constructor(clazz: Class<*>) : this(clazz.simpleName) {}

    /** Creates a Logger using the caller's class name as the message prefix.  */
    constructor(minLogLevel: Int) : this(DEFAULT_TAG, null) {
        this.minLogLevel = minLogLevel
    }

    fun setMinLogLevel(minLogLevel: Int) {
        this.minLogLevel = minLogLevel
    }

    fun isLoggable(logLevel: Int): Boolean {
        return logLevel >= minLogLevel || Log.isLoggable(tag, logLevel)
    }

    private fun toMessage(format: String, vararg args: Any): String {
        return messagePrefix + if (args.size > 0) String.format(format) else format
    }

    @SuppressLint("LogTagMismatch")
    fun v(format: String, vararg args: Any?) {
        if (isLoggable(Log.VERBOSE)) {
            Log.v(tag, toMessage(format))
        }
    }

    @SuppressLint("LogTagMismatch")
    fun v(t: Throwable?, format: String, vararg args: Any?) {
        if (isLoggable(Log.VERBOSE)) {
            Log.v(tag, toMessage(format), t)
        }
    }

    @SuppressLint("LogTagMismatch")
    fun d(format: String, vararg args: Any?) {
        if (isLoggable(Log.DEBUG)) {
            Log.d(tag, toMessage(format))
        }
    }

    @SuppressLint("LogTagMismatch")
    fun d(t: Throwable?, format: String, vararg args: Any?) {
        if (isLoggable(Log.DEBUG)) {
            Log.d(tag, toMessage(format), t)
        }
    }

    @SuppressLint("LogTagMismatch")
    fun i(format: String, vararg args: Any?) {
        if (isLoggable(Log.INFO)) {
            Log.i(tag, toMessage(format))
        }
    }

    @SuppressLint("LogTagMismatch")
    fun i(t: Throwable?, format: String, vararg args: Any?) {
        if (isLoggable(Log.INFO)) {
            Log.i(tag, toMessage(format), t)
        }
    }

    @SuppressLint("LogTagMismatch")
    fun w(format: String, vararg args: Any?) {
        if (isLoggable(Log.WARN)) {
            Log.w(tag, toMessage(format))
        }
    }

    @SuppressLint("LogTagMismatch")
    fun w(t: Throwable?, format: String, vararg args: Any?) {
        if (isLoggable(Log.WARN)) {
            Log.w(tag, toMessage(format), t)
        }
    }

    @SuppressLint("LogTagMismatch")
    fun e(format: String, vararg args: Any?) {
        if (isLoggable(Log.ERROR)) {
            Log.e(tag, toMessage(format))
        }
    }

    @SuppressLint("LogTagMismatch")
    fun e(t: Throwable?, format: String, vararg args: Any?) {
        if (isLoggable(Log.ERROR)) {
            Log.e(tag, toMessage(format), t)
        }
    }
    /**
     * Creates a Logger with a custom tag and a custom message prefix. If the message prefix is set to
     *
     * <pre>null</pre>
     *
     * , the caller's class name is used as the prefix.
     *
     * @param tag identifies the source of a log message.
     * @param messagePrefix prepended to every message if non-null. If null, the name of the caller is
     * being used
     */
    /** Creates a Logger using the caller's class name as the message prefix.  */
    init {
        val prefix =
            messagePrefix ?: callerSimpleName
        this.messagePrefix = if (prefix.length > 0) "$prefix: " else prefix
    }
}
