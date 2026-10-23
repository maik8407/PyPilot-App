package de.eneriq.pilot

/** Versionsvergleich fuer GitHub-Releases (tag_name wie "v1.2" oder "1.2.1"). */
object Version {
    /** Zahlenteile eines Tags; null, wenn kein Zahlenteil gefunden wird. "v1.2-beta" -> [1, 2]. */
    fun parse(tag: String): List<Int>? {
        val m = Regex("""(\d+(?:\.\d+)*)""").find(tag.trim().removePrefix("v").removePrefix("V")) ?: return null
        return m.value.split('.').map { it.toInt() }
    }

    /** true, wenn [tag] neuer ist als [installed]. Unlesbare Tags gelten nie als neuer. */
    fun isNewer(tag: String, installed: String): Boolean {
        val a = parse(tag) ?: return false
        val b = parse(installed) ?: return true
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
