package com.lifthelper.util

class CircularBuffer<T>(private val capacity: Int) {
    private val buffer = mutableListOf<T>()

    fun add(item: T) {
        if (buffer.size >= capacity) {
            buffer.removeAt(0)
        }
        buffer.add(item)
    }

    operator fun get(index: Int): T = buffer[index]

    val size: Int get() = buffer.size

    fun clear() = buffer.clear()

    fun toList(): List<T> = buffer.toList()

    fun first(): T = buffer.first()
    fun last(): T = buffer.last()
}
