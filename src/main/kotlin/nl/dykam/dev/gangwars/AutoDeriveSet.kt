package nl.dykam.dev.gangwars

interface Derivative<T> {
    fun add(value: T)
    fun remove(value: T)
    fun clear()
}

class AutoDeriveSet<T> : MutableCollection<T> {
    private val data: MutableSet<T> = mutableSetOf()
    private val derivatives: MutableList<Derivative<T>> = mutableListOf()

    fun <D : Derivative<T>> add(derivative: D): D {
        derivatives.add(derivative)
        return derivative
    }

    override fun add(element: T): Boolean {
        if(data.add(element)) {
            derivatives.forEach { it.add(element) }
            return true
        }
        return false
    }

    override fun addAll(elements: Collection<T>): Boolean {
        return elements.map { add(it) }.any()
    }

    override fun remove(element: T): Boolean {
        if(data.remove(element)) {
            derivatives.forEach { it.remove(element) }
            return true
        }
        return false
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        return elements.map { remove(it) }.any()
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        return data.retainAll(elements)
    }

    override val size: Int get() = data.size

    override fun contains(element: T): Boolean = data.contains(element)

    override fun containsAll(elements: Collection<T>): Boolean = data.containsAll(elements)

    override fun isEmpty(): Boolean = data.isEmpty()

    override fun clear() {
        data.clear()
        derivatives.forEach { it.clear() }
    }

    override fun iterator(): MutableIterator<T> = data.iterator()
}

interface MapDerivative<K, V>: Map<K, V>, Derivative<V>
interface MapMultiDerivative<K, V>: Map<K, Set<V>>, Derivative<V>

fun <K, V> createKeyDerivative(keySelector: (value: V) -> K): MapDerivative<K, V> {
    return object : HashMap<K, V>(), MapDerivative<K, V> {
        override fun add(value: V) {
            this[keySelector(value)] = value
        }

        override fun remove(value: V) {
            this.remove(keySelector(value))
        }
    }
}

fun <K, V> createMultiKeyDerivative(keySelector: (value: V) -> List<K>): MapDerivative<K, V> {
    return object : HashMap<K, V>(), MapDerivative<K, V> {
        override fun add(value: V) {
            keySelector(value).forEach { this[it] = value }
        }

        override fun remove(value: V) {
            keySelector(value).forEach { this.remove(it) }
        }
    }
}

fun <K, V> createKeyMultiDerivative(keySelector: (value: V) -> K): MapMultiDerivative<K, V> {
    return object : HashMap<K, Set<V>>(), MapMultiDerivative<K, V> {
        override fun add(value: V) {
            this += Pair(keySelector(value), value)
        }

        override fun remove(value: V) {
            this -= Pair(keySelector(value), value)
        }
    }
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