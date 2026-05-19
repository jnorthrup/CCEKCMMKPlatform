@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * ConfixScope — Axis 6: CCEK Lifecycle + Structured Fanout.
 *
 * Owns its lifecycle state machine. Uses ConfixIndex as the parse result —
 * each consumer projects the facets it needs.
 */

enum class ConfixLifecycle { Created, Open, Active, Draining, Closed }

object ConfixScopeKey : CoroutineContext.Key<ConfixScope>

fun interface ConfixSubscriber {
    suspend fun onResult(syntax: Syntax, index: ConfixIndex)
}

class ConfixSource(val syntax: Syntax, val src: Series<Char>)

class ConfixScope(
    val source: ConfixSource,
    parentContext: CoroutineContext? = null,
) : AbstractCoroutineContextElement(ConfixScopeKey) {

    private val supervisor: CompletableJob =
        if (parentContext == null) SupervisorJob() else SupervisorJob(parentContext[Job])

    var state: ConfixLifecycle = ConfixLifecycle.Created; private set
    private val subscribers = mutableListOf<ConfixSubscriber>()

    /** The scanned ConfixIndex — lazy, computed on first access. */
    val index: ConfixIndex by lazy { scannerFor(source.syntax).index(source.src) }

    /** Convenience: the reified tree cursor. */
    val reified: Cursor by lazy { ConfixReify.parse(source.src, source.syntax) }

    fun subscribe(subscriber: ConfixSubscriber) {
        check(state == ConfixLifecycle.Created || state == ConfixLifecycle.Open)
        subscribers.add(subscriber)
    }

    fun open() {
        check(state == ConfixLifecycle.Created)
        state = ConfixLifecycle.Open
    }

    suspend fun activate() {
        check(state == ConfixLifecycle.Open)
        state = ConfixLifecycle.Active
        val ix = index
        coroutineScope {
            for (sub in subscribers) launch(supervisor) { sub.onResult(source.syntax, ix) }
        }
        state = ConfixLifecycle.Draining
    }

    fun drain() { if (state != ConfixLifecycle.Closed) state = ConfixLifecycle.Draining }

    fun close() {
        if (state == ConfixLifecycle.Closed) return
        state = ConfixLifecycle.Closed
        supervisor.complete()
    }

    fun query(path: JsPath): ConfixIndex? = resolve(index, path, source.src)
}

/** Multi-source fanout — one scope per syntax, concurrent. */
class ConfixFanout(
    val sources: Series<ConfixSource>,
    parent: CoroutineContext? = null,
) : AbstractCoroutineContextElement(ConfixScopeKey) {

    private val supervisor: CompletableJob =
        if (parent == null) SupervisorJob() else SupervisorJob(parent[Job])

    var state: ConfixLifecycle = ConfixLifecycle.Created; private set
    private val subscribers = mutableListOf<ConfixSubscriber>()

    private val scopes: Series<ConfixScope> by lazy {
        sources.size j { i: Int -> ConfixScope(sources[i], supervisor) }
    }

    fun subscribe(subscriber: ConfixSubscriber) {
        check(state == ConfixLifecycle.Created || state == ConfixLifecycle.Open)
        subscribers.add(subscriber)
    }

    fun open() { check(state == ConfixLifecycle.Created); state = ConfixLifecycle.Open }

    suspend fun activate() {
        check(state == ConfixLifecycle.Open)
        state = ConfixLifecycle.Active
        coroutineScope {
            for (i in 0 until scopes.size) {
                val scope = scopes[i]
                launch(supervisor) {
                    scope.open()
                    val ix = scope.index
                    val subsSnap = subscribers.toList()
                    for (sub in subsSnap) launch(supervisor) { sub.onResult(scope.source.syntax, ix) }
                }
            }
        }
        state = ConfixLifecycle.Draining
    }

    fun drain() { if (state != ConfixLifecycle.Closed) state = ConfixLifecycle.Draining }

    fun close() {
        if (state == ConfixLifecycle.Closed) return
        state = ConfixLifecycle.Closed
        supervisor.complete()
    }

    fun query(path: JsPath): Series<ConfixIndex?> {
        val n = scopes.size
        return n j { i: Int -> scopes[i].query(path) }
    }
}
