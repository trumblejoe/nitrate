package dev.nitrate.desktop.mpv

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** libmpv without the library: records what Nitrate asks of it and hands back queued events. */
class FakeMpvApi : MpvApi {
    val options = mutableListOf<Pair<String, String>>()
    val commands = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
    val properties = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    val observed = mutableListOf<String>()
    val events = LinkedBlockingQueue<MpvEvent>()
    var initialized = false
    var destroyed = false
    var strings = mapOf<String, String>()

    override fun setOption(name: String, value: String): Int { options += name to value; return 0 }
    override fun initialize(): Int { initialized = true; return 0 }
    override fun command(vararg args: String): Int { commands += args.toList(); return 0 }
    override fun setProperty(name: String, value: String): Int { properties += name to value; return 0 }
    override fun getString(name: String): String? = strings[name]
    override fun getDouble(name: String): Double? = strings[name]?.toDoubleOrNull()
    override fun observe(id: Long, name: String, format: MpvFormat) { observed += name }
    override fun requestLog(level: String) = Unit
    override fun waitEvent(timeoutSeconds: Double): MpvEvent? = events.poll((timeoutSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)
    override fun wakeup() { events.offer(MpvEvent.Other(0)) }
    override fun softwareRenderer(onUpdate: () -> Unit): SoftwareRenderer? = null
    override fun errorString(code: Int) = "loading failed"
    override fun destroy() { destroyed = true }

    fun option(name: String) = options.lastOrNull { it.first == name }?.second
    fun property(name: String) = properties.lastOrNull { it.first == name }?.second
}
