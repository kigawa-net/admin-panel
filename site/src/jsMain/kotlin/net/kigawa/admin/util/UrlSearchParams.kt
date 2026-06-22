package net.kigawa.admin.util

external class URLSearchParams() {
    constructor(search: String)
    fun get(name: String): String?
    fun set(name: String, value: String)
    override fun toString(): String
}
