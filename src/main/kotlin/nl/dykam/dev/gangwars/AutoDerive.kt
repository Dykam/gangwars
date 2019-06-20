package nl.dykam.dev.gangwars

interface DerivativeHandle<T> {
    fun add(value: T)
    fun canAdd(value: T): Boolean
    fun remove(value: T)
    fun canRemove(value: T): Boolean
    fun clear()
}

interface Updateable<K, V> {
    fun update(key: K, updater: (current: V) -> V): V?
}

class AutoDerive<T> {
    private val derivativeHandles: MutableList<DerivativeHandle<T>> = mutableListOf()

    fun <C> add(creator: DerivativeCreator<T, C>): C {
        val (handle, derivative) = creator(this)
        derivativeHandles.add(handle)
        return derivative
    }

    fun add(element: T): Boolean {
        if (derivativeHandles.all { it.canAdd(element) }) {
            derivativeHandles.forEach { it.add(element) }
            return true
        }
        return false
    }

    fun addAll(elements: Collection<T>): Boolean {
        return elements.map { add(it) }.any()
    }

    fun remove(element: T): Boolean {
        if (derivativeHandles.all { it.canRemove(element) }) {
            derivativeHandles.forEach { it.remove(element) }
            return true
        }
        return false
    }

    fun removeAll(elements: Collection<T>): Boolean {
        return elements.map { remove(it) }.any()
    }

    fun clear() {
        derivativeHandles.forEach { it.clear() }
    }

    operator fun plusAssign(element: T) { add(element) }
    operator fun minusAssign(element: T) { remove(element) }
}

typealias DerivativeCreator<T, C> = (set: AutoDerive<T>) -> Pair<DerivativeHandle<T>, C>
interface UpdateableMap<K, V> : Map<K, V>, Updateable<K, V>

fun <V> createSetDerivative() : DerivativeCreator<V, Set<V>> = { set ->
    val data = mutableSetOf<V>()
    Pair(
        object : DerivativeHandle<V> {
            override fun add(value: V) { data.add(value) }

            override fun canAdd(value: V): Boolean = !data.contains(value)

            override fun remove(value: V) { data.remove(value) }

            override fun canRemove(value: V): Boolean = data.contains(value)

            override fun clear() { data.clear() }
        },
        data
    )
}

fun <K, V> createKeyDerivative(keySelector: (value: V) -> K): DerivativeCreator<V, UpdateableMap<K, V>> = { auto ->
    val data = object: UpdateableMap<K, V>, MutableMap<K, V> by mutableMapOf() {
        override fun update(key: K, updater: (current: V) -> V): V? {
            this[key]?.let {
                val updated = updater(it)
                auto -= it
                if(!auto.add(updated)) {
                    auto += it
                    return null
                }
                return updated
            }
            return null
        }
    }
    Pair(
        object : DerivativeHandle<V> {
            override fun add(value: V) { data[keySelector(value)] = value }

            override fun canAdd(value: V): Boolean = !data.containsKey(keySelector(value))

            override fun remove(value: V) { data.remove(keySelector(value)) }

            override fun canRemove(value: V): Boolean = data.containsKey(keySelector(value))

            override fun clear() { data.clear() }
        },
        data
    )
}

fun <K, V> createMultiKeyDerivative(keySelector: (value: V) -> List<K>): DerivativeCreator<V, UpdateableMap<K, V>> = { auto ->
    val data = object: UpdateableMap<K, V>, MutableMap<K, V> by mutableMapOf() {
        override fun update(key: K, updater: (current: V) -> V): V? {
            this[key]?.let {
                val updated = updater(it)
                auto -= it
                if (!auto.add(updated)) {
                    auto += it
                    return null
                }
                return updated
            }
            return null
        }
    }
    Pair(
        object : DerivativeHandle<V> {
            override fun add(value: V) { keySelector(value).forEach { data[it] = value } }

            override fun canAdd(value: V): Boolean = keySelector(value).all { !data.containsKey(it) }

            override fun remove(value: V) { keySelector(value).forEach { data.remove(it) } }

            override fun canRemove(value: V): Boolean = keySelector(value).all { data.containsKey(it) }

            override fun clear() { data.clear() }
        },
        data
    )
}

fun <K, V> createKeyMultiDerivative(keySelector: (value: V) -> K): DerivativeCreator<V, Map<K, Set<V>>> = { auto ->
    val data = mutableMapOf<K, Set<V>>()
    Pair(
        object : DerivativeHandle<V> {
            override fun canAdd(value: V): Boolean =
                    data[keySelector(value)]?.let { set -> !set.contains(value) } ?: true

            override fun canRemove(value: V): Boolean =
                    data[keySelector(value)]?.let { set -> set.contains(value) } ?: false

            override fun add(value: V) { data += Pair(keySelector(value), value) }

            override fun remove(value: V) { data -= Pair(keySelector(value), value) }

            override fun clear() { data.clear() }
        },
        data
    )
}


fun <K, V> MutableMap<K, Set<V>>.put(key: K, value: V) {
    this.compute(key, { _, set -> (set ?: setOf()) + value })
}
fun <K, V> MutableMap<K, Set<V>>.get(key: K) {
    this.compute(key, { _, set -> set ?: setOf() })
}
operator fun <K, V> MutableMap<K, Set<V>>.plusAssign(pair: Pair<K, V>) {
    val (key, value) = pair
    this.compute(key, { _, set -> (set ?: setOf()) + value })
}
operator fun <K, V> MutableMap<K, Set<V>>.minusAssign(pair: Pair<K, V>) {
    val (key, value) = pair
    this.compute(key, { _, set -> (set ?: setOf()) - value })
}