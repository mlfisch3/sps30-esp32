package com.sensirion.sps30.wire

import java.io.File

actual fun wireVectorsText(): String {
    val named = System.getenv("WIRE_VECTORS_JSON")?.let(::File)
    val file = if (named != null && named.isFile) named else {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "vectors/wire_vectors.json") }
            .firstOrNull { it.isFile }
            ?: error(
                "vectors/wire_vectors.json not found: set WIRE_VECTORS_JSON or " +
                    "generate it with python3 tools/gen_wire_vectors_json.py",
            )
    }
    return file.readText()
}
