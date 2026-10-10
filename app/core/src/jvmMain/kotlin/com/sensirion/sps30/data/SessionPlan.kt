package com.sensirion.sps30.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Deciding which stored sessions are worth reading, before any of them is read.
 *
 * Most of the stored record is files that carry no readings at all. In the
 * archive taken off the Pi on 2026-07-31, 163 of 245 sessions are an 88-byte
 * header line and nothing else - two thirds of the files and 0.008% of the
 * bytes. Counting those as work made the import's progress bar spend most of
 * its length on files that contribute nothing, and reach the real data at the
 * far right where it looked like a stall.
 *
 * So the run is planned first: every candidate is classified from its size and,
 * when it is small enough to be suspect, its first two lines. What is left is
 * the set of files that actually carry readings, and that set - not the file
 * count - is what the progress bar measures against and what the averaging
 * window is sized from.
 *
 * **Nothing here writes, moves or removes a file.** A session classified as
 * empty is left exactly where it is and reported in the summary; being left out
 * of an import is not the same as being thrown away, and the difference is the
 * whole reason this is a plan rather than a cleanup.
 */
object SessionPlan {

    /**
     * One stored session as the planner sees it: a name, a size, and a way to
     * read its bytes.
     *
     * [source] is carried through untouched so the caller can find the file
     * again - which archive it came out of, typically - without the planner
     * knowing anything about where stored data lives.
     */
    class Candidate<T>(
        val name: String,
        val bytes: Long,
        val source: T,
        /** Opens this file's bytes, or returns null if it cannot be opened. */
        val open: () -> InputStream?,
    )

    /**
     * What one import run should read, and an account of what it should not.
     *
     * The three exclusions are counted rather than merely dropped, because a
     * summary that reports only what was loaded reads as "all of it".
     */
    class Plan<T>(
        /** The sessions that carry readings, in the order to read them. */
        val read: List<Candidate<T>>,
        /** Uncompressed bytes behind [read] alone, for sizing the average. */
        val bytes: Long,
        /**
         * Every session found to hold a header and no readings.
         *
         * Kept by name, not merely counted: these files are still on the device
         * and the user is entitled to be told which ones they are. A thousand
         * names is some 25 KB, which is not worth reducing to a number.
         */
        val emptyNames: List<String>,
        /** Files whose header could not be made sense of at all. */
        val unrecognised: Int,
        /** Sessions already held, from an earlier archive or an earlier run. */
        val duplicate: Int,
    ) {
        val empty: Int get() = emptyNames.size
        val total: Int get() = read.size
    }

    /**
     * Plan a run over [candidates], which must be in the order they should be
     * read - newest first, so that when consecutive archives overlap the newer
     * copy of a session is the one taken.
     *
     * [alreadyImported] reports sessions the history is already holding from an
     * earlier run, which are duplicates in exactly the same way.
     */
    fun <T> build(
        candidates: List<Candidate<T>>,
        alreadyImported: (String) -> Boolean = { false },
    ): Plan<T> {
        val read = ArrayList<Candidate<T>>()
        val emptyNames = ArrayList<String>()
        val seen = HashSet<String>()
        var unrecognised = 0
        var duplicate = 0
        var bytes = 0L
        for (c in candidates) {
            // Name is identity: a session appears in every archive made after
            // it, and reading it twice adds nothing but time.
            if (!seen.add(c.name) || alreadyImported(c.name)) {
                duplicate++
                continue
            }
            when (classify(c)) {
                SessionCsv.Skip.EMPTY -> emptyNames.add(c.name)
                SessionCsv.Skip.UNRECOGNISED -> unrecognised++
                null -> {
                    read.add(c)
                    bytes += c.bytes.coerceAtLeast(0L)
                }
            }
        }
        return Plan(read, bytes, emptyNames, unrecognised, duplicate)
    }

    /**
     * What [c] is, as cheaply as it can be known.
     *
     * Big files are taken on trust; small ones are opened and their first two
     * lines read. Anything that goes wrong - unopenable, unreadable - answers
     * null, so the file goes to the full read and is judged there. The planner
     * never concludes "empty" from a failure.
     */
    private fun <T> classify(c: Candidate<T>): SessionCsv.Skip? {
        if (c.bytes > SessionCsv.PROBE_MAX_BYTES) return null
        val stream = runCatching { c.open() }.getOrNull() ?: return null
        return runCatching {
            stream.use { SessionCsv.probe(BufferedReader(InputStreamReader(it), 4096)) }
        }.getOrNull()
    }
}
