package com.mobilecontroller.client.net

/**
 * The PCs this phone connected to, most recent first, so the settings offer them without a search and a PC whose
 * address changed (a new DHCP lease) can be found again by its name. Pure Kotlin; persisted by Settings as one string.
 */
class KnownPcs(entries: List<KnownPc> = emptyList()) {
    data class KnownPc(val name: String, val host: String, val port: Int)

    private val list = ArrayList(entries.take(MAX))

    val all: List<KnownPc> get() = list

    /** Puts [pc] first; an entry with the same name (or, unnamed, the same address) is replaced. */
    fun remember(pc: KnownPc) {
        list.removeAll { it.sameAs(pc) }
        list.add(0, pc)
        while (list.size > MAX) list.removeAt(list.size - 1)
    }

    fun byName(name: String): KnownPc? = list.firstOrNull { it.name.isNotEmpty() && it.name == name }

    private fun KnownPc.sameAs(other: KnownPc) =
        if (name.isNotEmpty() && other.name.isNotEmpty()) name == other.name else host == other.host && port == other.port

    /** One line per PC: name, host and port separated by tabs (none of them can contain a tab or a line break). */
    fun serialize(): String = list.joinToString("\n") { "${clean(it.name)}\t${clean(it.host)}\t${it.port}" }

    companion object {
        const val MAX = 8

        fun parse(text: String?): KnownPcs {
            if (text.isNullOrEmpty()) return KnownPcs()
            val entries = text.split('\n').mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3 || parts[1].isEmpty()) return@mapNotNull null
                val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
                KnownPc(parts[0], parts[1], port)
            }
            return KnownPcs(entries)
        }

        private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ')
    }
}
