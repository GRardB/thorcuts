package com.grardb.thorcuts.core.safety

/**
 * Validation and quoting for every string that reaches a shell running as uid 0.
 *
 * This is not defence in depth for its own sake. Package names and activity class
 * names reach Thorcuts from OTHER apps (accessibility events name the foreground
 * package; a HOME activity's name comes out of `dumpsys`), and they end up inside
 * commands that run as root. An unvalidated value with a quote in it is a root
 * shell for whoever chose the value.
 *
 * Rule: validate FIRST, then quote. Never interpolate an unvalidated string.
 */
object CommandSafety {

    const val MAX_PACKAGE_LENGTH = 255
    const val MAX_COMPONENT_LENGTH = 400
    const val MAX_CLASS_NAME_LENGTH = 255

    /** A real Android package name: dotted, starts with a letter, nothing a shell reads. */
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * `pkg/class` as `am start -n` takes it. The class half legitimately contains
     * '.' and '$' (inner classes), so it gets its own character set.
     */
    private val COMPONENT = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+$")

    /**
     * A fully-qualified JVM class name: dotted, each segment starts with a letter,
     * '_' or '$' and continues with those plus digits. Inner classes legitimately
     * contain '$' (e.g. com.foo.Outer$Inner), same as the class half of COMPONENT.
     */
    private val CLASS_NAME = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$")

    fun isPackage(s: String?): Boolean =
        s != null && s.length <= MAX_PACKAGE_LENGTH && PACKAGE.matches(s)

    fun isComponent(s: String?): Boolean =
        s != null && s.length <= MAX_COMPONENT_LENGTH && COMPONENT.matches(s)

    fun isClassName(s: String?): Boolean =
        s != null && s.length <= MAX_CLASS_NAME_LENGTH && CLASS_NAME.matches(s)

    /**
     * True if [s] has no line break. A value carrying a newline would inject a
     * second command into a shell, or a second message into the helper's
     * line-oriented protocol.
     */
    fun isSingleLine(s: String): Boolean = s.none { it == '\n' || it == '\r' }

    /**
     * Single-quote [s] for `sh`, whatever it contains. A single quote cannot be
     * escaped inside single quotes, so each one closes the string, adds an escaped
     * quote, and reopens: `a'b` becomes `'a'\''b'`.
     */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** A shell-ready package argument, or null if [s] is not a package name. */
    fun packageArg(s: String?): String? = if (isPackage(s)) quote(s!!) else null

    /** A shell-ready component argument, or null if [s] is not a component name. */
    fun componentArg(s: String?): String? = if (isComponent(s)) quote(s!!) else null

    /** A shell-ready class-name argument, or null if [s] is not a class name. */
    fun classNameArg(s: String?): String? = if (isClassName(s)) quote(s!!) else null
}
