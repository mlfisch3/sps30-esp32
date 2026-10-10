package com.sensirion.sps30.ui

import com.sensirion.sps30.data.GapStanding
import com.sensirion.sps30.data.RecordGap
import com.sensirion.sps30.data.SensorRetention
import com.sensirion.sps30.data.TimeSeriesHistory
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire

/**
 * The rules a backfill runs by, kept apart from [MonitorSession] so they can be
 * stated - and tested - without a Context, a radio or a board.
 *
 * A backfill is the app asking the controller for readings it does not have:
 * `get_file <name> since_index=<n>`, one session at a time. It runs on every
 * connect and every transport switch, which is what makes the three rules below
 * worth being exact about. They were each wrong in a way that only shows up when
 * something else is happening at the same time.
 */

/** One `get_file` the app means to send. */
data class BackfillRequest(
    val name: String,
    /** Ask for everything after this index. Zero means the whole session. */
    val since: Int,
    /**
     * True when this request exists to fill the archive a "copy everything" is
     * writing, rather than to fill the plot. Only these rows are written into the
     * zip: a narrow request that was already in flight when the copy started
     * carries the tail of a session and nothing more, and letting it into the
     * archive would put those rows in twice, once from it and once from the full
     * copy that follows.
     */
    val forArchive: Boolean = false,
)

/**
 * How long a request may go unanswered before the queue moves on.
 *
 * There has to be some deadline, because a refused `get_file` is answered with an
 * `error` that the app cannot tell from any other error (the protocol's `error`
 * carries no correlation to the command that caused it), so the queue can no
 * longer be advanced by an error arriving. Fifteen seconds is the same window
 * `MonitorSession` already treats as an unambiguously dead stream, and the
 * controller answers a `get_file` it is willing to serve in milliseconds.
 *
 * The deadline is measured from the last sign of life rather than from the
 * request, so a long transfer that is still delivering frames is never given up
 * on, and one that dies half way through is.
 */
const val BACKFILL_STALL_MS = 15_000L

/**
 * The queue of sessions to collect, and the one request in flight.
 *
 * One at a time: the controller serves `get_file` from a single cursor, so two
 * overlapping requests would truncate the first.
 */
class BackfillQueue(private val stallMs: Long = BACKFILL_STALL_MS) {

    private val queue = ArrayDeque<BackfillRequest>()
    private var inFlight: BackfillRequest? = null
    private var lastSignMs = 0L

    /** The request being served, if any. */
    val current: BackfillRequest? get() = inFlight
    val inFlightName: String? get() = inFlight?.name
    /** What is waiting, in the order it will be asked for. */
    val waiting: List<BackfillRequest> get() = queue.toList()
    val size: Int get() = queue.size

    fun clear() {
        queue.clear()
        inFlight = null
    }

    /**
     * Drop what is waiting but leave the request in flight alone.
     *
     * The controller is still streaming that one and its cursor cannot be
     * cancelled, so forgetting it here would let a second `get_file` go out
     * alongside it and truncate both.
     */
    fun clearWaiting() {
        queue.clear()
    }

    /**
     * Add a request, folding it into one already there for the same session.
     *
     * De-duplication used to be on the name alone and threw the `since` away, and
     * the two callers do not ask for the same thing: a reconnect asks for the live
     * session from where this device's copy stops being complete, while "copy
     * everything" asks for it from index 0 because the point of it is to be
     * complete. Dropping the second because the first was already queued was how a
     * copy taken shortly after connecting got only the tail of the live session
     * and still reported that it had succeeded.
     *
     * So the merge keeps the **more inclusive** of the two: the lower `since`, and
     * `forArchive` if either wants it. A request that asks for strictly more than
     * the one already in flight is queued to run after it rather than dropped,
     * because the cursor cannot be rewound mid-transfer.
     */
    fun enqueue(request: BackfillRequest) {
        if (request.name.isEmpty()) return
        val running = inFlight
        if (running != null && running.name == request.name && subsumes(running, request)) return
        val at = queue.indexOfFirst { it.name == request.name }
        if (at < 0) {
            queue.addLast(request)
            return
        }
        queue[at] = merge(queue[at], request)
    }

    fun enqueue(name: String, since: Int, forArchive: Boolean = false) =
        enqueue(BackfillRequest(name, since, forArchive))

    /** Take the next request and mark it in flight, or null if one already is. */
    fun start(nowMs: Long): BackfillRequest? {
        if (inFlight != null) return null
        val next = queue.removeFirstOrNull() ?: return null
        inFlight = next
        lastSignMs = nowMs
        return next
    }

    /** A `file_data` frame arrived, so the request in flight is alive. */
    fun progressed(name: String, nowMs: Long) {
        if (inFlight?.name == name) lastSignMs = nowMs
    }

    /** The controller said `done` for [name]. Returns the request that finished. */
    fun finish(name: String): BackfillRequest? {
        val running = inFlight ?: return null
        if (running.name != name) return null
        inFlight = null
        return running
    }

    /**
     * Give up a request the controller has stopped answering, so the rest of the
     * queue still runs. Returns it, or null if there is nothing to give up on.
     */
    fun giveUpIfStalled(nowMs: Long): BackfillRequest? {
        val running = inFlight ?: return null
        if (nowMs - lastSignMs < stallMs) return null
        inFlight = null
        return running
    }

    private fun subsumes(running: BackfillRequest, request: BackfillRequest): Boolean =
        request.since >= running.since && (!request.forArchive || running.forArchive)

    private fun merge(a: BackfillRequest, b: BackfillRequest) = BackfillRequest(
        name = a.name,
        since = minOf(a.since, b.since),
        forArchive = a.forArchive || b.forArchive,
    )
}

/**
 * How many stored sessions one *automatic* backfill collects.
 *
 * A Raspberry Pi answers `list_files` with everything it has ever recorded, which
 * can be hundreds, and asking for all of them on every reconnect would be a large
 * transfer to fill a plot that only keeps two days. Newest first, so the cap drops
 * the sessions already off the left edge of the chart.
 *
 * The floor is the controller's own ceiling - `STORE_MAX_SESSIONS`, 32 - so a
 * reconnect to a full board still reaches every session it holds. That is the
 * half of this that was wrong: the number was written as 24 when the board held
 * 16, the board's ceiling moved to 32 and this did not, so eight sessions on a
 * full board could never reach the plot. Raise the two together.
 */
const val MAX_BACKFILL_SESSIONS = 32

/**
 * How many sessions this pass may queue.
 *
 * **A copy the user asked for has no cap.** "Everything the sensor has recorded"
 * means everything: an archive that quietly stops at [MAX_BACKFILL_SESSIONS]
 * sessions is not a backup, and because the erase gate only offers the controller
 * what the archive can prove, a board with more sessions than that could never be
 * fully cleared through the app either. One number was serving two situations -
 * bounding a reconnect's cost, and bounding a backup - and only the first of them
 * wants a bound at all.
 */
fun backfillSessionCap(forArchive: Boolean): Int =
    if (forArchive) Int.MAX_VALUE else MAX_BACKFILL_SESSIONS

/**
 * What to ask the controller for, given everything it says it holds.
 *
 * [files] is `file_list`, newest first - which is the order the controller and
 * the daemon both send, and the order that matters when the cap bites, because
 * the sessions it drops are then the ones already off the left edge of the chart.
 *
 * [forArchive] is the difference between the two situations this serves: filling
 * a plot, which is bounded, and writing the backup the user asked for, which is
 * not. See [backfillSessionCap].
 */
fun backfillPlan(
    files: List<ServerMessage.FileEntry>,
    forArchive: Boolean,
    held: Map<String, TimeSeriesHistory.Coverage>,
    receivedEver: (String) -> Int,
): List<BackfillRequest> {
    val cap = backfillSessionCap(forArchive)
    val plan = ArrayList<BackfillRequest>()
    for (entry in files) {
        if (plan.size >= cap) break
        // Where this device's copy stops being complete, which is where the
        // controller has to start. NOT the highest index seen: after a transport
        // switch the readings that arrived over the new link have already pushed
        // that past the seam, and asking from there would step straight over the
        // hole and fetch nothing. Null is a session there is nothing to ask for;
        // see [backfillSinceFor] for what that can and cannot be proved from.
        val since = backfillSinceFor(
            controllerRows = entry.rows,
            receivedEver = receivedEver(entry.name),
            held = held[entry.name],
            forArchive = forArchive,
        ) ?: continue
        plan.add(BackfillRequest(entry.name, since, forArchive = forArchive))
    }
    return plan
}

/**
 * The `list_files` offset to ask for next, or null when this pass is finished.
 *
 * A `file_list` carries what one frame can hold and says where the rest begin;
 * the board holds more sessions than one frame can name - 84 against 64, measured
 * on 2026-08-04, and one worse on every restart - so a pass that reads a single
 * frame is short by however many the frame could not carry.
 *
 * Which pass it is decides whether that matters, exactly as [backfillSessionCap]
 * decides whether a cap does, and for the same reason:
 *
 * - **A copy walks the whole listing.** "Everything the sensor has recorded"
 *   means everything. An archive short of the controller's own count cannot be a
 *   backup, and because the erase gate only offers what the archive proves, a
 *   board whose listing is longer than one frame could never be cleared either.
 * - **The automatic backfill reads one page.** It fills a plot that keeps two
 *   days, one page already names more sessions than [MAX_BACKFILL_SESSIONS]
 *   would queue from it, and newest-first means that page is the part of the
 *   listing the plot can still show. Walking every page of an eighty-session
 *   board on every reconnect would be a long transfer to fetch readings that are
 *   then dropped for age.
 *
 * [ServerMessage.FileList.next] is already guarded against a controller that
 * answers with its own offset, so a walk cannot be made to loop by whatever is on
 * the other end of the wire.
 */
fun listingWalkNext(
    page: ServerMessage.FileList,
    forArchive: Boolean,
    /**
     * A reconciliation sweep, which walks for a third reason: it has to know
     * where the sensor's record *begins*, and the oldest session is the last
     * entry of a newest-first listing. Stopping at one page would leave the
     * retained floor sitting on the oldest session a frame happened to carry,
     * and every hole below that would be reported lost when the sensor may well
     * still have it. See [SensorRetention.of].
     */
    reconciling: Boolean = false,
): Int? = if (forArchive || reconciling) page.next else null

// --- reconciling this device's record against the sensor's -------------------

/**
 * How often the app compares its own record against the sensor's.
 *
 * Half an hour, and the number is chosen against the thing it is racing: the
 * board's ring holds about 2.36 days at 1 Hz, so a hole gets on the order of a
 * hundred chances to be noticed and closed before the readings that would close
 * it are overwritten. Anything much longer starts to matter at the tail of that
 * window; anything much shorter buys nothing, because nothing about the problem
 * moves in minutes.
 *
 * What a sweep costs when there is nothing to do is one `list_files` walk - two
 * frames on the measured 84-session board - and no `get_file` at all. It is the
 * fill that is expensive, and a fill only happens when there is a hole to fill.
 */
const val RECONCILE_EVERY_MS = 30 * 60_000L

/**
 * How long after connecting the first sweep runs.
 *
 * Not immediately: `hello` already starts the ordinary backfill, and a sweep on
 * top of it would be a second `list_files` walking past a queue that is about to
 * make the answer stale. Ninety seconds is long enough for an ordinary reconnect
 * to have drained and short enough that plugging the tablet back in is still the
 * thing that closed the hole, as far as anyone watching is concerned.
 */
const val RECONCILE_AFTER_CONNECT_MS = 90_000L

/**
 * How long a sweep waits when the link is busy with something else.
 *
 * A sweep never competes for the controller's cursor. A copy, a send or an
 * ordinary backfill all own it, and one `get_file` alongside another truncates
 * both - so a sweep that arrives mid-transfer stands down and asks again a
 * minute later rather than queueing behind work of unknown length.
 */
const val RECONCILE_RETRY_MS = 60_000L

/**
 * The longest a sweep may be in progress before it is abandoned.
 *
 * A backstop behind the per-page deadline rather than a duplicate of it. Every
 * page of a listing is already given [BACKFILL_STALL_MS] to answer, and each of
 * the ways that deadline can fail to be armed - a first `list_files` swallowed by
 * a copy that clears it, a link replaced mid-walk - leaves a sweep marked as
 * running with nothing left that could finish it. Nothing else starts a sweep
 * while one is running, so that state is permanent, and a feature that silently
 * stops working after one unlucky moment is worse than one that never worked.
 * Two minutes is far past any real walk (two pages on the measured 84-session
 * board) and short enough that the next half-hourly sweep is unaffected.
 */
const val RECONCILE_GIVE_UP_MS = 120_000L

/** What the one-second tick should do about reconciliation. */
enum class SweepAction {
    /** Nothing yet: not due, or one is running and still within its time. */
    WAIT,

    /** Ask the controller what it holds. */
    START,

    /** Due, but the link is not free. Try again in [RECONCILE_RETRY_MS]. */
    STAND_DOWN,

    /** A sweep was started and never answered. Abandon it so another can run. */
    GIVE_UP,
}

/**
 * Whether to sweep now, given everything the tick can see.
 *
 * A function rather than a `when` inside the model, for the same reason
 * [streamIsDead] and `autoExportStep` are: the interesting part is the *order* of
 * the arms, and the order is what can be got wrong without anything failing
 * visibly. Two of them matter especially.
 *
 * - **Running is asked about before due.** Otherwise a sweep that outlives its
 *   own half-hour would be started a second time, and two `list_files` walks
 *   interleaving in one [sensorListing] would reconcile against a listing that is
 *   half of each.
 * - **Busy is a stand-down, not a queue.** The controller serves `get_file` from
 *   a single cursor, so a sweep behind a copy of unknown length would be asking
 *   about a listing that is stale before it arrives - and would be holding the
 *   cursor away from the transfer the user is actually waiting on. Nothing about
 *   a gap moves in a minute, so it simply asks again.
 */
fun sweepStep(
    nowMs: Long,
    dueAtMs: Long,
    running: Boolean,
    startedAtMs: Long,
    connected: Boolean,
    linkBusy: Boolean,
): SweepAction = when {
    running && nowMs - startedAtMs >= RECONCILE_GIVE_UP_MS -> SweepAction.GIVE_UP
    running -> SweepAction.WAIT
    dueAtMs == 0L || nowMs < dueAtMs -> SweepAction.WAIT
    !connected || linkBusy -> SweepAction.STAND_DOWN
    else -> SweepAction.START
}

/**
 * Where a `get_file` has to start to reach the missing part of a session.
 *
 * The rule the reconnect backfill gets right for one shape of hole and wrong for
 * the other, which is why this is a second function rather than a parameter on
 * [backfillSinceFor]:
 *
 * - **A hole inside the copy** - indices 0..400 and 600..900 held - is what
 *   [TimeSeriesHistory.Coverage.contiguousTo] describes, and asking from there
 *   re-sends 400 onwards. Correct, and no more than an ordinary reconnect costs.
 * - **A copy that starts late** - 12,000..188,000 held, nothing before it - has
 *   no hole by that test at all, because `contiguousTo` counts up from the lowest
 *   index held. Asking from it fetches only readings this device already has,
 *   which is precisely what happened on 2026-08-04: the backfill filled forward
 *   from the reconnect and stepped straight over three hours. The protocol has
 *   one cursor and it only runs forwards, so reaching the front of a session
 *   means asking for the whole of it.
 *
 * The second case re-sends readings this device holds. That costs time and
 * nothing else - rows de-duplicate by (session, index) on the way in - and it is
 * bounded by a sweep that will not ask for the same gap twice (see [gapKey]).
 * Asking for less costs the data.
 */
fun gapFillSince(held: TimeSeriesHistory.Coverage?): Int = when {
    held == null || held.rows == 0 -> 0
    held.lowestIndex > 0 -> 0
    else -> held.contiguousTo
}

/**
 * The `get_file`s that would close [gaps], and no others.
 *
 * A gap is a stretch of *time* and `get_file` names a *session*, so the join
 * between them is the listing: each dated entry states when its session began,
 * and the board's record is contiguous, so one session runs until the next one
 * starts. That is the whole of the mapping - it needs no interval, no row
 * arithmetic and no guess about how long a session lasted.
 *
 * Only [GapStanding.Fillable] gaps produce requests, and each is asked for from
 * [RecordGap.fillableFrom] rather than from its own start: where the ring has
 * already eaten the front of a hole there is no session to name for that part,
 * and naming one anyway would fetch a session for nothing.
 *
 * One request per session however many gaps fall in it: where a `get_file` has to
 * start is a property of this device's copy, not of any one hole in it, so two
 * holes in one session are one transfer that closes both. `forArchive` is false
 * throughout: a fill puts readings into the plot, and it must not be able to
 * write into the zip a copy is building. Everything else about the transfer - one
 * at a time, the stall deadline, de-duplication on the way in - is
 * [BackfillQueue]'s, unchanged.
 */
fun gapFillPlan(
    gaps: List<RecordGap>,
    files: List<ServerMessage.FileEntry>,
    held: Map<String, TimeSeriesHistory.Coverage>,
): List<BackfillRequest> {
    // Oldest first, each paired with where the next one takes over. A session
    // with no date cannot be placed on this axis at all and is left out: it is
    // not a session that might cover the gap, it is one nothing can say that
    // about. Its readings are still collected by the ordinary backfill.
    val dated = files
        .mapNotNull { entry -> SensorRetention.datedStart(entry)?.let { entry to it } }
        .sortedBy { it.second }
    if (dated.isEmpty()) return emptyList()

    val since = LinkedHashMap<String, Int>()
    for (gap in gaps) {
        val from = gap.fillableFrom ?: continue
        for ((position, pair) in dated.withIndex()) {
            val (entry, startsAt) = pair
            // The next session's start, or nothing after it if this is the newest.
            val endsAt = dated.getOrNull(position + 1)?.second ?: Double.MAX_VALUE
            if (endsAt <= from || startsAt >= gap.endEpoch) continue
            since.getOrPut(entry.name) { gapFillSince(held[entry.name]) }
        }
    }
    return since.map { (name, from) -> BackfillRequest(name, from, forArchive = false) }
}

/**
 * A stable name for one gap, so a sweep can tell it has already been for it.
 *
 * Whole seconds, because the two epochs are readings this device holds and are
 * therefore exactly reproducible across sweeps until one of them is filled in or
 * trimmed away - at which point the gap genuinely is a different gap and asking
 * again is right.
 *
 * The guard this feeds matters more than it looks. A gap the sensor claims to
 * cover but cannot actually supply - a session listed with a start earlier than
 * the readings it still holds - would otherwise have its whole session re-fetched
 * on every sweep, for ever, and a reconciliation that re-reads the board twice an
 * hour is exactly the "do not hammer the sensor" failure in a helpful costume.
 * Asking once and then leaving it alone is what makes the automatic sweep safe;
 * the manual button is what lets the user say "try again anyway".
 */
fun gapKey(gap: RecordGap): String = "${gap.startEpoch.toLong()}..${gap.endEpoch.toLong()}"

/**
 * What one `file_list` entry is worth asking for, or null when it is not.
 *
 * The completeness test used to be "do I still hold at least as many readings as
 * the controller says it has", and that comparison is only fair when this
 * device's retention is at least as long as the controller's ring. It is not:
 * the app keeps 48 hours and the board holds about 56, so once the board has been
 * running longer than the retention window the test can never pass and the
 * session is asked for again on every connect, forever.
 *
 * [receivedEver] is the honest left-hand side. It counts readings that arrived,
 * which retention does not take back, so a session collected in full stays
 * collected. A hole is checked separately and always wins, because a copy with a
 * gap in it is short whatever the counts say.
 *
 * What this still cannot prove: `file_list` names a row count and no last index,
 * so a session whose front the ring has dropped could match on count while this
 * device is missing its tail. Asking again costs a round trip and asking too
 * little costs data, so the hole test is deliberately the strict one.
 */
fun backfillSinceFor(
    controllerRows: Int,
    receivedEver: Int,
    held: TimeSeriesHistory.Coverage?,
    forArchive: Boolean = false,
): Int? {
    // A copy has to be complete, so it asks for everything regardless of coverage.
    if (forArchive) return 0
    if (controllerRows <= 0) return null
    if (held != null && held.contiguousTo < held.highestIndex) return held.contiguousTo
    if (receivedEver >= controllerRows) return null
    return held?.contiguousTo ?: 0
}

/**
 * Whether the live session is worth asking for the moment `hello` names it,
 * before `file_list` has said how much of it the controller holds.
 *
 * `hello` is answered with `list_files` in the same breath, so a session not
 * asked for here is asked for one message later by [backfillSinceFor] with the
 * controller's own count in hand. That makes this purely a head start, and it is
 * worth taking only when this device can already see that it is missing
 * something: a hole in its copy, or no copy at all. Asking unconditionally is
 * what made the live session a wasted round trip on every single connect.
 */
fun liveSessionNeedsHeadStart(held: TimeSeriesHistory.Coverage?): Boolean =
    held == null || held.rows == 0 || held.contiguousTo < held.highestIndex

/** What an `error` from the controller should do to the app's state. */
data class ErrorHandling(
    /** Show the refusal to the user. */
    val showDialog: Boolean,
    /** Fail a "copy everything" that is waiting to hear back. */
    val failsCopy: Boolean,
    /** Abandon the `get_file` in flight. */
    val cancelsBackfill: Boolean,
)

/** The commands "copy everything" issues, so an `error` tagged with one of them
 *  is answering the copy and not something the user did meanwhile. */
private val COPY_COMMANDS = setOf(Wire.CMD_LIST_FILES, Wire.CMD_GET_FILE, Wire.CMD_BACKUP)

/**
 * Decide what an `error` means, given what else the app has running.
 *
 * [ref] is the command the controller says the error answers. Where it is stated
 * this is a fact; where it is blank - an older controller, or a failure belonging
 * to no request - the rule below stands unchanged, which is what it always did.
 * Two lanes each added a
 * transfer to the list of things that "consume" an error, and together they made
 * a backfill - which is running on every connect and after every transport
 * switch - swallow refusals that had nothing to do with it, and cancel itself on
 * refusals of other commands. Tapping a mode chip shortly after connecting was
 * enough to get both.
 *
 * So a running backfill now decides nothing. It is deliberately still a
 * parameter, because "is a transfer running" is exactly the question that used to
 * be answered wrongly here, and the answer is that it does not bear on either
 * outcome:
 *
 * - **The refusal is always shown.** A `set_profile` refusal is sent with
 *   `needs_control = false`, and suppressing it leaves a mode chip that appears
 *   to do nothing while the controller has said precisely why it will not move.
 * - **The transfer is never cancelled.** A `get_file` that really was refused is
 *   given up on by [BackfillQueue.giveUpIfStalled] instead, which does not have
 *   to guess who the error belonged to.
 *
 * [copyRequested] is left as it was. That state lasts only from the moment "copy
 * everything" sends `list_files` until the first frame comes back, and an error
 * inside that window is answering the request the copy just made.
 */
fun errorHandlingFor(
    needsControl: Boolean,
    copyRequested: Boolean,
    @Suppress("UNUSED_PARAMETER") backfillInFlight: Boolean,
    ref: String = Wire.ERROR_REF_NONE,
): ErrorHandling {
    // An untagged error is treated as concerning whatever is in flight, exactly as
    // before the field existed. A tagged one that names something else - a refused
    // `clean`, a refused `set_profile` - leaves the copy alone and is shown, which
    // is the whole point of carrying the tag.
    val failsCopy = copyRequested && (ref.isEmpty() || ref in COPY_COMMANDS)
    return ErrorHandling(
        showDialog = needsControl || !failsCopy,
        failsCopy = failsCopy,
        cancelsBackfill = false,
    )
}
