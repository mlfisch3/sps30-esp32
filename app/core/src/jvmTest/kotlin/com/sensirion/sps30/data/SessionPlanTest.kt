package com.sensirion.sps30.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader

/**
 * Recognising the stored sessions that hold nothing, before reading them.
 *
 * Sized from the real archive taken off the Pi on 2026-07-31: 245 sessions,
 * 190.5 MB of CSV, of which 163 files are an 88-byte header line and nothing
 * else. Two thirds of the files and 0.008% of the bytes, which is why counting
 * files made the import's progress bar useless.
 */
class SessionPlanTest {

    private val header =
        "index,timestamp,pm1_0,pm2_5,pm4_0,pm10,nc0_5,nc1_0,nc2_5,nc4_0,nc10,typical_size,event"

    /** Byte-for-byte what the daemon leaves when a session records nothing. */
    private val emptyFile = header + "\r\n"

    private fun row(index: Int, second: Int) =
        "%07d,2026-07-27T%02d:%02d:%02d-04:00,7.2,9.1,10.3,10.5,45.6,56.2,58.0,58.3,58.4,0.62,\r\n"
            .format(index, 2 + second / 3600, (second / 60) % 60, second % 60)

    private fun session(rows: Int): String =
        buildString {
            append(emptyFile)
            for (i in 1..rows) append(row(i, i))
        }

    private fun candidate(name: String, text: String) =
        SessionPlan.Candidate<String>(name, text.toByteArray().size.toLong(), name) {
            ByteArrayInputStream(text.toByteArray())
        }

    @Test fun theEmptyFileIsExactlyTheHeaderLine() {
        // 88 bytes: the CSV header the daemon writes on opening a session, with
        // the CRLF that csv.DictWriter emits, and no data row after it.
        assertEquals(88, emptyFile.toByteArray().size)
    }

    @Test fun aHeaderOnlyFileIsRecognisedWithoutReadingIt() {
        assertEquals(
            SessionCsv.Skip.EMPTY,
            SessionCsv.probe(BufferedReader(StringReader(emptyFile))),
        )
    }

    @Test fun aFileWithOneReadingIsNotCalledEmpty() {
        assertNull(SessionCsv.probe(BufferedReader(StringReader(session(1)))))
    }

    @Test fun aCompletelyBlankFileIsEmptyRatherThanAnError() {
        assertEquals(SessionCsv.Skip.EMPTY, SessionCsv.probe(BufferedReader(StringReader(""))))
    }

    @Test fun aHeaderFollowedOnlyByBlankLinesIsEmpty() {
        // read() skips blank lines, so probe has to agree or the two would
        // disagree about the same file.
        assertEquals(
            SessionCsv.Skip.EMPTY,
            SessionCsv.probe(BufferedReader(StringReader(header + "\n\n\n"))),
        )
    }

    @Test fun somethingThatIsNotSessionDataIsUnrecognisedNotEmpty() {
        assertEquals(
            SessionCsv.Skip.UNRECOGNISED,
            SessionCsv.probe(BufferedReader(StringReader("name,quantity\nwidget,2"))),
        )
    }

    @Test fun theEmptiesAreLeftOutOfTheProgressTotal() {
        // The real proportions: 163 header-only, 82 with readings.
        val candidates = buildList {
            for (i in 1..163) add(candidate("SPS30-2026072702%04d.csv".format(i), emptyFile))
            for (i in 1..82) add(candidate("SPS30-2026072804%04d.csv".format(i), session(40)))
        }
        val plan = SessionPlan.build(candidates)
        assertEquals(82, plan.total)
        assertEquals(163, plan.empty)
        assertEquals(0, plan.unrecognised)
        // What the bar used to do: 67% of its length on 0.008% of the work.
        assertEquals(0.665, 163.0 / 245.0, 0.01)
    }

    @Test fun everyEmptyIsNamedSoNoneOfThemIsMerelyAMissingNumber() {
        val plan = SessionPlan.build(
            listOf(
                candidate("SPS30-20260727_021339.csv", emptyFile),
                candidate("SPS30-20260727_021340.csv", emptyFile),
                candidate("SPS30-20260728_022000.csv", session(5)),
            ),
        )
        assertEquals(
            listOf("SPS30-20260727_021339.csv", "SPS30-20260727_021340.csv"),
            plan.emptyNames,
        )
        assertEquals(1, plan.total)
    }

    @Test fun theAveragingWindowIsSizedFromTheReadableSessionsOnly() {
        val plan = SessionPlan.build(
            listOf(
                candidate("SPS30-a.csv", emptyFile),
                candidate("SPS30-b.csv", session(10)),
            ),
        )
        assertEquals(session(10).toByteArray().size.toLong(), plan.bytes)
    }

    @Test fun aSessionTwoArchivesBothHoldIsPlannedOnce() {
        // Each archive contains every session recorded before it, so the older
        // one is almost entirely a repeat of the newer.
        val plan = SessionPlan.build(
            listOf(
                candidate("SPS30-a.csv", session(3)),
                candidate("SPS30-a.csv", session(3)),
                candidate("SPS30-b.csv", session(3)),
            ),
        )
        assertEquals(2, plan.total)
        assertEquals(1, plan.duplicate)
    }

    @Test fun aSessionAlreadyInTheHistoryIsNotPlannedAgain() {
        val plan = SessionPlan.build(
            listOf(candidate("SPS30-a.csv", session(3)), candidate("SPS30-b.csv", session(3))),
        ) { it == "SPS30-a.csv" }
        assertEquals(1, plan.total)
        assertEquals("SPS30-b.csv", plan.read.single().name)
        assertEquals(1, plan.duplicate)
    }

    @Test fun aLargeFileIsTakenOnTrustRatherThanProbed() {
        var opened = 0
        val big = SessionPlan.Candidate<String>(
            "SPS30-big.csv", SessionCsv.PROBE_MAX_BYTES + 1, "big",
        ) {
            opened++
            ByteArrayInputStream(session(1).toByteArray())
        }
        val plan = SessionPlan.build(listOf(big))
        assertEquals(1, plan.total)
        assertEquals("a file past the probe limit was opened", 0, opened)
    }

    @Test fun aFileThatCannotBeOpenedIsReadAnywayRatherThanCalledEmpty() {
        // The safety property: nothing is concluded to be empty from a failure.
        val broken = SessionPlan.Candidate<String>("SPS30-x.csv", 88L, "x") { null }
        val plan = SessionPlan.build(listOf(broken))
        assertEquals(1, plan.total)
        assertEquals(0, plan.empty)
    }

    @Test fun aFileThatThrowsWhileBeingProbedIsReadAnyway() {
        val angry = SessionPlan.Candidate<String>("SPS30-x.csv", 88L, "x") {
            object : InputStream() {
                override fun read(): Int = throw java.io.IOException("no")
            }
        }
        val plan = SessionPlan.build(listOf(angry))
        assertEquals(1, plan.total)
        assertEquals(0, plan.empty)
    }

    @Test fun probeNeverContradictsAFullRead() {
        // The two classifications have to agree, or a file would be skipped by
        // one rule and counted by the other.
        val files = listOf(emptyFile, header, header + "\n\n", session(1), session(60), "")
        for (text in files) {
            val probed = SessionCsv.probe(BufferedReader(StringReader(text)))
            val full = SessionCsv.read(BufferedReader(StringReader(text)), 1.0) { _, _, _, _ -> }
            if (probed != null) {
                assertEquals("disagreed about ${text.take(20)}", probed, full.skip)
            }
        }
    }

    @Test fun everyPlannedSessionIsStillOnDiskAfterwards() {
        // The plan is a decision about what to read. It is not a decision about
        // what to keep, and it has no way to become one: a Candidate only ever
        // offers a stream to read.
        val reads = ArrayList<String>()
        val c = SessionPlan.Candidate<String>("SPS30-a.csv", 88L, "a") {
            reads.add("SPS30-a.csv")
            ByteArrayInputStream(emptyFile.toByteArray())
        }
        val plan = SessionPlan.build(listOf(c))
        assertEquals(0, plan.total)
        assertEquals(1, plan.empty)
        assertTrue("the file was only ever opened for reading", reads == listOf("SPS30-a.csv"))
    }
}
