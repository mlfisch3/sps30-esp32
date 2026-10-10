package com.sensirion.sps30.wire

/**
 * The text of `vectors/wire_vectors.json`, however this platform reads a file.
 *
 * The one deliberately platform-specific line in the wire contract's test:
 * common code has no file API and no environment API, so each target answers
 * with what it has - `System.getenv` and `java.io.File` on the JVM, POSIX
 * `getenv`/`fopen` on native. Every implementation tries the
 * `WIRE_VECTORS_JSON` environment variable first (the Gradle build sets it),
 * then walks up from the working directory looking for
 * `vectors/wire_vectors.json`, and THROWS when neither finds the file:
 * a missing contract must fail the pack, never skip it.
 */
expect fun wireVectorsText(): String
