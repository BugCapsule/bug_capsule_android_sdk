package dev.bugcapsule.android.monitoring

/**
 * Data attached to every event, transaction and feedback sent while it is
 * current: user, tags, extras, contexts, level, fingerprint, breadcrumbs and
 * the active span. The global scope is changed with
 * [dev.bugcapsule.android.BugCapsule.configureScope] (or the `set*` shortcuts);
 * [dev.bugcapsule.android.BugCapsule.withScope] runs a block with a temporary
 * copy on the calling thread. Thread-safe.
 */
class Scope internal constructor(maxBreadcrumbs: Int) {
    private val lock = Any()
    private var user: User? = null
    private val tags = LinkedHashMap<String, String>()
    private val extras = LinkedHashMap<String, Any?>()
    private val contexts = LinkedHashMap<String, Map<String, Any?>>()
    private var level: Level? = null
    private var fingerprint: List<String>? = null
    private val breadcrumbs = ArrayDeque<Breadcrumb>()
    private var span: Span? = null

    @Volatile
    internal var maxBreadcrumbs: Int = maxBreadcrumbs.coerceIn(0, 100)
        set(value) {
            field = value.coerceIn(0, 100)
            synchronized(lock) { trim() }
        }

    fun setUser(user: User?) = synchronized(lock) { this.user = user }

    fun getUser(): User? = synchronized(lock) { user }

    fun setTag(key: String, value: String) = synchronized(lock) { tags[key] = value }

    fun setTags(tags: Map<String, String>) = synchronized(lock) { this.tags.putAll(tags) }

    fun removeTag(key: String) = synchronized(lock) { tags.remove(key) }

    fun setExtra(key: String, value: Any?) = synchronized(lock) { extras[key] = value }

    fun setExtras(extras: Map<String, Any?>) = synchronized(lock) { this.extras.putAll(extras) }

    fun removeExtra(key: String) = synchronized(lock) { extras.remove(key) }

    /** Sets `contexts.<name>` (null removes it). `device`, `os`, `app` and `trace` are filled by the SDK. */
    fun setContext(name: String, value: Map<String, Any?>?) = synchronized(lock) {
        if (value == null) contexts.remove(name) else contexts[name] = value
    }

    /** Overrides the level of every event captured with this scope. */
    fun setLevel(level: Level?) = synchronized(lock) { this.level = level }

    /** Custom grouping for every event captured with this scope (monitoring-v1 §4.2). */
    fun setFingerprint(fingerprint: List<String>?) = synchronized(lock) { this.fingerprint = fingerprint }

    internal fun addBreadcrumb(breadcrumb: Breadcrumb) = synchronized(lock) {
        breadcrumbs.addLast(breadcrumb)
        trim()
    }

    fun clearBreadcrumbs() = synchronized(lock) { breadcrumbs.clear() }

    /** The span new spans (HTTP, database, ...) attach to; screen transactions bind themselves here. */
    var activeSpan: Span?
        get() = synchronized(lock) { span }
        set(value) = synchronized(lock) { span = value }

    /** Clears everything except breadcrumbs. */
    fun clear() = synchronized(lock) {
        user = null
        tags.clear()
        extras.clear()
        contexts.clear()
        level = null
        fingerprint = null
        span = null
    }

    private fun trim() {
        while (breadcrumbs.size > maxBreadcrumbs) breadcrumbs.removeFirst()
    }

    internal fun copy(): Scope = synchronized(lock) {
        Scope(maxBreadcrumbs).also {
            it.user = user
            it.tags.putAll(tags)
            it.extras.putAll(extras)
            it.contexts.putAll(contexts)
            it.level = level
            it.fingerprint = fingerprint
            it.breadcrumbs.addAll(breadcrumbs)
            it.span = span
        }
    }

    internal class Snapshot(
        val user: User?,
        val tags: Map<String, String>,
        val extras: Map<String, Any?>,
        val contexts: Map<String, Map<String, Any?>>,
        val level: Level?,
        val fingerprint: List<String>?,
        val breadcrumbs: List<Breadcrumb>,
        val span: Span?,
    )

    internal fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(user, LinkedHashMap(tags), LinkedHashMap(extras), LinkedHashMap(contexts), level, fingerprint, breadcrumbs.toList(), span)
    }
}

/** Changes the global scope. */
fun interface ScopeCallback {
    fun configure(scope: Scope)
}
