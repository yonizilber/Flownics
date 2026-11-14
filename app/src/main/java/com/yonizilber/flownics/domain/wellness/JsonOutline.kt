package com.yonizilber.flownics.domain.wellness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shape of a JSON answer, one line per field: its path, its kind and an example value. A
 * list is shown by its length and its first item, so a day of readings is one line, not 700.
 */
object JsonOutline {

    data class Line(val path: String, val kind: String, val example: String?)

    fun lines(root: JsonElement, max: Int = 400): List<Line> {
        val out = mutableListOf<Line>()
        fun walk(e: JsonElement, path: String) {
            if (out.size >= max) return
            when (e) {
                is JsonObject -> {
                    if (path.isNotEmpty()) out += Line(path, "{${e.size}}", null)
                    e.forEach { (k, v) -> walk(v, if (path.isEmpty()) k else "$path.$k") }
                }
                is JsonArray -> {
                    out += Line(path.ifEmpty { "(root)" }, "[${e.size}]", null)
                    e.firstOrNull()?.let { walk(it, "$path[0]") }
                }
                JsonNull -> out += Line(path, "null", null)
                is JsonPrimitive -> out += Line(path, if (e.isString) "text" else "value", e.content.take(EXAMPLE))
            }
        }
        walk(root, "")
        return out
    }

    private const val EXAMPLE = 48
}
