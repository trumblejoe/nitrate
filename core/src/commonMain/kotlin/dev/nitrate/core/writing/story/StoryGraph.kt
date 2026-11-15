package dev.nitrate.core.writing.story

import kotlin.math.max
import kotlin.math.sqrt

/** What a line on the map means; each kind has its own colour and can be filtered. */
enum class EdgeKind(val label: String) {
    /** A character speaks in a scene (from the script). */
    APPEARS("Appears in"),
    /** A scene is set in a location (from its heading). */
    SET_IN("Set in"),
    /** A link a writer made between two entries. */
    RELATES("Relationship"),
    /** A link a writer made between a scene and an entry (or two scenes). */
    LINKED("Linked to scene"),
}

data class MapNode(
    val ref: Ref,
    val label: String,
    /** The list an entry is in; null for scenes. */
    val listId: String?,
    /** A scene's place in the script. */
    val sceneIndex: Int? = null,
)

data class MapEdge(val from: Ref, val to: Ref, val kind: EdgeKind, val label: String = "", val linkId: String? = null)

/** What to show on the map. */
data class MapFilter(
    val edgeKinds: Set<EdgeKind> = EdgeKind.entries.toSet(),
    /** Lists whose entries show (null: all). */
    val lists: Set<String>? = null,
    val showScenes: Boolean = true,
    /** Only this node and what it's joined to. */
    val focus: Ref? = null,
    /** Hide entries joined to nothing shown. */
    val hideLoose: Boolean = false,
)

data class StoryMap(val nodes: List<MapNode>, val edges: List<MapEdge>)

/**
 * The relationship map: scenes and list entries, joined by lines from the script itself
 * (characters speaking in scenes, scenes' locations) and by links writers made.
 */
object StoryGraph {

    fun build(story: Story, scenes: List<Scene>): StoryMap {
        val lists = story.lists()
        val nodes = mutableListOf<MapNode>()
        scenes.filter { it.id != null }.forEach { nodes += MapNode(Ref.scene(it.id!!), it.heading, null, it.index) }
        val entriesByList = lists.associateWith { story.listItems(it, scenes) }
        entriesByList.forEach { (list, entries) -> entries.forEach { nodes += MapNode(Ref.entry(it.id), it.name, list.id) } }
        val edges = mutableListOf<MapEdge>()
        entriesByList.forEach { (list, entries) ->
            val kind = when (list.kind) { ListKind.CHARACTERS -> EdgeKind.APPEARS; ListKind.LOCATIONS -> EdgeKind.SET_IN; else -> null } ?: return@forEach
            entries.forEach { e -> story.scenesOf(e, list, scenes).mapNotNull { it.id }.forEach { edges += MapEdge(Ref.entry(e.id), Ref.scene(it), kind) } }
        }
        val known = nodes.map { it.ref }.toSet()
        story.links().filter { it.from in known && it.to in known }.forEach { l ->
            val kind = if (l.from.kind == Ref.Kind.ENTRY && l.to.kind == Ref.Kind.ENTRY) EdgeKind.RELATES else EdgeKind.LINKED
            edges += MapEdge(l.from, l.to, kind, l.label, l.id)
        }
        return StoryMap(nodes, edges)
    }

    fun filter(map: StoryMap, f: MapFilter): StoryMap {
        var nodes = map.nodes.filter { n -> if (n.listId == null) f.showScenes else f.lists == null || n.listId in f.lists }
        var shown = nodes.map { it.ref }.toSet()
        var edges = map.edges.filter { it.kind in f.edgeKinds && it.from in shown && it.to in shown }
        f.focus?.takeIf { it in shown }?.let { focus ->
            edges = edges.filter { it.from == focus || it.to == focus }
            val near = edges.flatMap { listOf(it.from, it.to) }.toSet() + focus
            nodes = nodes.filter { it.ref in near }
            shown = near
        }
        if (f.hideLoose) {
            val joined = edges.flatMap { listOf(it.from, it.to) }.toSet()
            nodes = nodes.filter { it.listId == null || it.ref in joined }
        }
        return StoryMap(nodes, edges)
    }

    /**
     * Positions in a 0..1 square: scenes along a line through the middle in script order, entries
     * pulled towards what they're joined to and pushed apart from each other. The same map always
     * lays out the same way (start points come from the ids), so it doesn't jump around.
     */
    fun layout(map: StoryMap, iterations: Int = 200): Map<Ref, Pair<Float, Float>> {
        if (map.nodes.isEmpty()) return emptyMap()
        val scenes = map.nodes.filter { it.sceneIndex != null }.sortedBy { it.sceneIndex }
        val free = map.nodes.filter { it.sceneIndex == null }
        val pos = HashMap<Ref, FloatArray>()
        scenes.forEachIndexed { i, n -> pos[n.ref] = floatArrayOf(if (scenes.size == 1) 0.5f else 0.06f + 0.88f * i / (scenes.size - 1), 0.5f) }
        free.forEach { n ->
            val h = n.ref.toString().hashCode()
            pos[n.ref] = floatArrayOf(0.1f + 0.8f * ((h and 0xffff) / 65535f), if ((h ushr 16) and 1 == 0) 0.2f else 0.8f)
        }
        val k = 0.6f / sqrt(max(1, map.nodes.size).toFloat())
        val neighbours = map.edges.flatMap { listOf(it.from to it.to, it.to to it.from) }.groupBy({ it.first }, { it.second })
        repeat(iterations) { step ->
            val heat = 0.05f * (1f - step.toFloat() / iterations) + 0.002f
            for (n in free) {
                val p = pos.getValue(n.ref)
                var dx = 0f
                var dy = 0f
                // Pushed away from every other node.
                for (o in map.nodes) {
                    if (o.ref == n.ref) continue
                    val q = pos.getValue(o.ref)
                    val ex = p[0] - q[0]
                    val ey = p[1] - q[1]
                    val d2 = max(ex * ex + ey * ey, 1e-5f)
                    dx += ex * k * k / d2
                    dy += ey * k * k / d2
                }
                // Pulled towards what it's joined to.
                neighbours[n.ref]?.forEach { r ->
                    val q = pos[r] ?: return@forEach
                    val ex = q[0] - p[0]
                    val ey = q[1] - p[1]
                    val d = sqrt(ex * ex + ey * ey)
                    dx += ex * d / k
                    dy += ey * d / k
                }
                // Off the scene line, so lines to scenes stay readable.
                if (kotlin.math.abs(p[1] - 0.5f) < 0.08f) dy += if (p[1] < 0.5f) -0.02f else 0.02f
                val len = max(sqrt(dx * dx + dy * dy), 1e-6f)
                p[0] = (p[0] + dx / len * minOf(len, heat)).coerceIn(0.03f, 0.97f)
                p[1] = (p[1] + dy / len * minOf(len, heat)).coerceIn(0.03f, 0.97f)
            }
        }
        return pos.mapValues { (_, p) -> p[0] to p[1] }
    }

    /** The nearest shown node in a direction (for moving across the map with a D-pad). */
    fun nearest(from: Ref, dx: Int, dy: Int, positions: Map<Ref, Pair<Float, Float>>): Ref? {
        val (x, y) = positions[from] ?: return positions.keys.firstOrNull()
        return positions.filterKeys { it != from }.mapNotNull { (r, p) ->
            val ex = p.first - x
            val ey = p.second - y
            val along = ex * dx + ey * dy
            if (along <= 0.001f) return@mapNotNull null
            val across = kotlin.math.abs(ex * dy - ey * dx)
            r to (along + 2.5f * across)
        }.minByOrNull { it.second }?.first
    }
}
