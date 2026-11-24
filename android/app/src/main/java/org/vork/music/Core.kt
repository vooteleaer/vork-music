package org.vork.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The music core (Rust, `android/jni`). Every call blocks: call it off the
 * main thread. Answers are JSON; errors are a string, "" when fine.
 */
object Core {
    init {
        System.loadLibrary("vork_music")
    }

    /** `vork`: the class whose static `connect()` gives connections. */
    external fun init(vork: Class<*>)
    /** The libraries this app may use: `[{name, writable}]`, or `{"error"}`. */
    external fun libraries(): String
    /** The albums of library `ns`, each with its tracks and cover, or `{"error"}`. */
    external fun albums(ns: String): String
    /** Bytes `offset..offset+len` of `cap` (fewer at its end), or null if not had within `waitMs`. */
    external fun readRange(ns: String, cap: String, offset: Long, len: Long, waitMs: Long): ByteArray?
    /** Block `cap` of a record in `ns` if at most `max` bytes, else null. */
    external fun read(ns: String, cap: String, max: Long): ByteArray?
    external fun setFavorite(ns: String, id: String, on: Boolean): String
    /** `{library: change count}`. */
    external fun changeCounts(libraries: String): String
    /** Waits (at most `timeoutMs`) until a library in `known` changes: `{changed, known}`. */
    external fun waitChange(known: String, timeoutMs: Long): String
    /** Where the core writes its own panics (a folder, and the header each report starts with). */
    external fun crashDir(dir: String, header: String)
    /** A crash or trouble report to vØrk storage (`kind`, from `source`): true once it took it. */
    external fun crashReport(kind: String, source: String, text: String): Boolean
}

suspend fun <T> core(work: () -> T): T = withContext(Dispatchers.IO) { work() }
