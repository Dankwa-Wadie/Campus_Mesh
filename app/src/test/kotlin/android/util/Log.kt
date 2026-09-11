@file:JvmName("Log")

package android.util

// Stand-in for the android.util.Log stubs, which throw "not mocked" under plain JVM unit tests.
// Every overload the production code calls has to exist here: a missing one surfaces at runtime as
// NoSuchMethodError (an Error, not an Exception), so it slips straight past `catch (e: Exception)`
// and fails the test somewhere far from the real cause.

fun d(tag: String, msg: String): Int {
    println("DEBUG: $tag: $msg")
    return 0;
}

fun d(tag: String, msg: String, tr: Throwable?): Int {
    println("DEBUG: $tag: $msg")
    tr?.printStackTrace()
    return 0;
}

fun e(tag: String, msg: String): Int {
    println("ERROR: $tag: $msg")
    return 0;
}

fun e(tag: String, msg: String, tr: Throwable?): Int {
    println("ERROR: $tag: $msg")
    tr?.printStackTrace()
    return 0;
}

fun w(tag: String, msg: String): Int {
    println("WARN: $tag: $msg")
    return 0;
}

fun w(tag: String, msg: String, tr: Throwable?): Int {
    println("WARN: $tag: $msg")
    tr?.printStackTrace()
    return 0;
}

fun w(tag: String, tr: Throwable?): Int {
    println("WARN: $tag")
    tr?.printStackTrace()
    return 0;
}

fun v(tag: String, msg: String): Int {
    println("VERBOSE: $tag: $msg")
    return 0;
}

fun v(tag: String, msg: String, tr: Throwable?): Int {
    println("VERBOSE: $tag: $msg")
    tr?.printStackTrace()
    return 0;
}

fun i(tag: String, msg: String): Int {
    println("INFO: $tag: $msg")
    return 0;
}

fun i(tag: String, msg: String, tr: Throwable?): Int {
    println("INFO: $tag: $msg")
    tr?.printStackTrace()
    return 0;
}

fun wtf(tag: String, msg: String): Int {
    println("ASSERT: $tag: $msg")
    return 0;
}

fun wtf(tag: String, msg: String, tr: Throwable?): Int {
    println("ASSERT: $tag: $msg")
    tr?.printStackTrace()
    return 0;
}

fun isLoggable(tag: String, level: Int): Boolean = true

fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""
