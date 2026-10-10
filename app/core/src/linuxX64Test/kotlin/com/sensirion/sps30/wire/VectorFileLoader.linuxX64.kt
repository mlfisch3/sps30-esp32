package com.sensirion.sps30.wire

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
private fun readWholeFile(path: String): String? {
    val file = fopen(path, "rb") ?: return null
    try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file)
        if (size <= 0) return null
        fseek(file, 0, SEEK_SET)
        val bytes = ByteArray(size.toInt())
        val read = bytes.usePinned { pinned ->
            fread(pinned.addressOf(0), 1u, size.toULong(), file)
        }
        if (read.toLong() != size) return null
        return bytes.decodeToString()
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun wireVectorsText(): String {
    val tried = mutableListOf<String>()
    getenv("WIRE_VECTORS_JSON")?.toKString()?.let { named ->
        tried += named
        readWholeFile(named)?.let { return it }
    }
    // The test binary's working directory is somewhere inside the repository;
    // walking up from it finds the one vectors/ directory wherever the runner
    // happened to start.
    for (depth in 0..12) {
        val candidate = "../".repeat(depth) + "vectors/wire_vectors.json"
        tried += candidate
        readWholeFile(candidate)?.let { return it }
    }
    error(
        "vectors/wire_vectors.json not found (tried: ${tried.joinToString()}); " +
            "set WIRE_VECTORS_JSON or generate it with python3 tools/gen_wire_vectors_json.py",
    )
}
