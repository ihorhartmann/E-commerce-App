package com.hartmann.core.model

/** One page of a list, as every list endpoint returns it; [number] is zero-based. */
data class Page<T>(val items: List<T>, val number: Int, val totalPages: Int) {
    fun hasPrevious(): Boolean = number > 0

    fun hasNext(): Boolean = number + 1 < totalPages

    companion object {
        fun <T> empty(): Page<T> = Page(emptyList(), 0, 0)
    }
}
