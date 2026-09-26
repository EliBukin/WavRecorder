package com.example.wavrecorder

import android.util.Log

/** An input device as Android reports it (`AudioDeviceInfo`, or `AudioRecord.getRoutedDevice()`),
 * reduced to what negotiation and route checks compare. */
data class InputDevice(val id: Int, val type: Int, val label: String) {
    val isExternal: Boolean get() = isExternalInputType(type)
}

/**
 * Which physical input a negotiation phase is for: the device to ask Android for, and what the
 * device actually routed once a candidate is recording must be for that candidate to count.
 */
internal sealed class RouteTarget {
    /** Passed to `AudioRecord.setPreferredDevice` for every candidate; null leaves routing to Android. */
    abstract val requested: InputDevice?

    /** True when a candidate whose route Android never reports must be rejected (a specific
     * external device is required); false when an unreported route is simply "unverified". */
    open val requiresConfirmedRoute: Boolean get() = false

    /** Null when [routed] -- the device a started candidate is actually routed to, or null when
     * Android can't say -- satisfies this target; otherwise why not. */
    abstract fun problemWith(routed: InputDevice?): String?

    /** An attached external microphone, whose capabilities produced the candidates: each one must
     * be routed to exactly this device, or it describes a format another input is delivering. */
    data class External(val device: InputDevice) : RouteTarget() {
        override val requested: InputDevice get() = device
        override val requiresConfirmedRoute: Boolean get() = true
        override fun problemWith(routed: InputDevice?): String? = when {
            routed == null -> "Android did not report the routed input, so ${device.label} could not be confirmed"
            routed.id != device.id || routed.type != device.type ->
                "routed to ${routed.label} (id ${routed.id}) instead of ${device.label} (id ${device.id})"
            else -> null
        }
    }

    /** No external microphone attached: default routing, exactly as before -- whichever input
     * Android picks is accepted and reported as it is (unverified when Android can't say). */
    object DefaultInput : RouteTarget() {
        override val requested: InputDevice? get() = null
        override fun problemWith(routed: InputDevice?): String? = null
    }

    /**
     * The phone's microphone, tried only after the attached external [unusable] microphone failed
     * in every format. [builtIn] is requested explicitly, because default routing would most likely
     * pick the external device again, and a route to any external input is rejected. The Record
     * screen then reports the phone microphone as not the expected one and lets the user retry or
     * continue with it, as it always has; a route Android can't report stays "unverified".
     */
    data class PhoneMicInstead(val builtIn: InputDevice?, val unusable: InputDevice) : RouteTarget() {
        override val requested: InputDevice? get() = builtIn
        override fun problemWith(routed: InputDevice?): String? =
            if (routed != null && routed.isExternal) "routed to the external ${routed.label}, not the phone microphone" else null
    }
}

/** One input device to negotiate for: where it must be routed, and the capabilities that produce
 * its candidate formats. */
internal data class CapturePhase(val target: RouteTarget, val capabilities: DeviceCapabilities)

/**
 * One candidate `AudioRecord`, through the lifecycle negotiation takes it: its client format is
 * checked, its preferred device requested, then it's started and its actual route verified while
 * it records. The seam that lets that lifecycle -- and every failure along it -- be tested without
 * a real microphone; production wraps a real AudioRecord (see [openBestAudioRecord]).
 */
internal interface CaptureCandidate {
    /** Read size in bytes, whole frames. */
    val bufferSize: Int

    /** Null when initialized and delivering exactly [requested]'s rate, encoding and channel count. */
    fun clientFormatProblem(requested: PcmFormat): String?

    /** `AudioRecord.setPreferredDevice`: false when Android refuses the request. */
    fun requestDevice(device: InputDevice): Boolean

    fun startRecording()

    /** True once recording has actually started: AudioRecord doesn't throw when the platform fails
     * to start it, it just stays stopped. */
    fun isRecording(): Boolean

    /** The device capture is actually routed to right now, or null when Android can't say. */
    fun routedDevice(): InputDevice?

    /** The device-side capture format, from the active recording configuration, when available. */
    fun deviceSideFormat(): DeviceSideFormat?

    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun stop()
    fun release()
}

/** Watches for an input device being removed (production: an `AudioDeviceCallback`). */
internal fun interface DeviceRemovalWatcher {
    /** Starts watching [deviceId], calling [onRemoved] once it's removed; closing the result stops. */
    fun watch(deviceId: Int, onRemoved: () -> Unit): AutoCloseable
}

/** One verification of a candidate -- a first try or a reopen -- and what Android reported for it
 * then (diagnostics). */
internal data class ObservedCandidate(
    val attempt: NegotiationAttempt,
    val deviceFormat: DeviceSideFormat?,
    val mode: CaptureMode,
    val reopen: Boolean = false
)

/** The candidate negotiation accepted: already recording, on a verified route. */
internal class CaptureOutcome<C : CaptureCandidate>(
    val candidate: C,
    val attempt: NegotiationAttempt,
    val target: RouteTarget,
    /** The device verified as routed (null: Android couldn't say, which only [RouteTarget.DefaultInput]
     * and [RouteTarget.PhoneMicInstead] accept). */
    val routed: InputDevice?,
    /** What Android reports the device side captures, read for the accepted (possibly reopened)
     * candidate itself; null when not reported. */
    val deviceFormat: DeviceSideFormat?,
    /** Attempts that failed (couldn't be built, validated, started or routed). */
    val rejected: List<RejectedAttempt>,
    /** Verified candidates that were compared and lost to this one. */
    val superseded: Int = 0,
    /** True when this candidate had been stopped while alternatives were compared, and was opened
     * and verified again from scratch. */
    val reopened: Boolean = false,
    /** How far the search got (see [SearchReport]). */
    val search: SearchReport = SearchReport(SearchCoverage.COMPLETE, SearchStop.NOTHING_BETTER_POSSIBLE),
    /** Every verification -- first tries and reopens -- in the order made (diagnostics). */
    val observed: List<ObservedCandidate> = emptyList()
) {
    val mode: CaptureMode get() = CaptureQuality.modeOf(attempt.candidate.format, deviceFormat)
}

/**
 * How far a quality search got -- kept apart from the chosen format's evidence ([CaptureMode]): a
 * complete search can end on an unreported capture, and an incomplete one on a matched capture.
 */
enum class SearchCoverage {
    /** Every candidate in the defined set that could still have improved on the result under the
     * quality policy was tried, or none remained. Not a proof of the device's maximum: the
     * candidate set is finite, and only exploratory where the device leaves a capability
     * unspecified ([FormatNegotiation]). */
    COMPLETE,

    /** A usable result, but the search ended before trying every candidate that could still have
     * improved on it: the best format *found*, not a verified maximum. */
    INCOMPLETE,

    /** No usable candidate was found. */
    NONE_USABLE
}

/** Why a quality search ended. */
enum class SearchStop {
    /** Every candidate was tried. */
    EXHAUSTED,

    /** No untried candidate could have scored higher than the result. */
    NOTHING_BETTER_POSSIBLE,

    /** The time for improving on the first usable candidate ([NegotiationLimits.searchBudgetMs]) ran out. */
    SEARCH_BUDGET,

    /** The negotiation's overall deadline ([NegotiationLimits.totalMs]) ran out. */
    DEADLINE,

    /** A candidate that scored higher than the result, when last verified, couldn't be reopened
     * and verified again (or time ran out before it could be): a lower one was used. */
    BEST_NOT_REOPENED
}

/** How far a quality search got, and why it ended (see [negotiateCapture]). */
data class SearchReport(
    val coverage: SearchCoverage,
    val stop: SearchStop,
    /** Candidates never tried that could still have improved on the result. */
    val untriedImprovers: Int = 0,
    /** Of those, the ones that could have been a *higher* format (a higher credited level), rather
     * than only stronger evidence or a tie-break for the same one. */
    val untriedHigherFormats: Int = 0,
    /** Candidates opened, reopens included. */
    val attempts: Int = 0,
    /** Distinct candidates that were started and verified. */
    val verified: Int = 0,
    /** Reopens that verified at a lower score than the same candidate had before. */
    val downgradedOnReopen: Int = 0
) {
    val isComplete: Boolean get() = coverage == SearchCoverage.COMPLETE

    override fun toString(): String =
        "$coverage ($stop): $attempts opened, $verified verified" +
            (if (untriedImprovers > 0) ", $untriedImprovers untried that could have improved on the result" else "") +
            (if (untriedHigherFormats > 0) " ($untriedHigherFormats of them a higher format)" else "") +
            (if (downgradedOnReopen > 0) "; $downgradedOnReopen reopen(s) verified lower than before" else "")
}

/**
 * Time and effort limits for [negotiateCapture]. Negotiation runs on the audio worker, never the
 * main thread (see [AudioStartup]), so these bound how long *startup* can take, not UI
 * responsiveness; [AudioStartup]'s watchdog separately bounds the user-visible wait, including a
 * native call that never returns.
 */
internal data class NegotiationLimits(
    /**
     * Per started candidate, when an external microphone is the target: how long its routed device
     * may take to be reported -- or to become that microphone -- before the candidate is rejected.
     * Every candidate gets its own full window. `getRoutedDevice()` is normally known as soon as
     * `startRecording()` returns; the window covers a USB input stream that is slow to open or
     * re-route (a USB audio HAL opening the device, or switching its alternate setting for a new
     * rate, commonly takes up to a few hundred ms), with margin, without letting a candidate that
     * will never report its route hold startup for long.
     */
    val routeSettleMs: Long = 500,
    /** How often the routed device (and device-side format) is re-read within a window. */
    val pollMs: Long = 20,
    /** Per started candidate, when no external microphone is targeted and an unreported route is
     * acceptable (reported as unverified): just long enough to pick up one reported a moment late. */
    val optionalRouteSettleMs: Long = 100,
    /**
     * How long to wait for the device-side format (`AudioRecordingConfiguration.getFormat()`) of a
     * started candidate. Android publishes the recording configuration asynchronously, normally
     * within tens of ms of the start; the first candidate of a phase gets [firstDeviceFormatSettleMs]
     * to tell a slow report from none at all, every later one this window -- including after
     * earlier candidates reported nothing, since a later one may still report it.
     */
    val deviceFormatSettleMs: Long = 100,
    val firstDeviceFormatSettleMs: Long = 200,
    /**
     * Scheduling only. After this many verified candidates in a row without a device-side report
     * -- and none with one -- the search next tries whatever can win on the evidence it already
     * has (typically the compatibility format, credited without a report), so a solid result is
     * in hand early; then it carries on with every candidate that could still win *with* a
     * report. Missing reports never remove a candidate from the search: a later candidate may
     * report one, and only the time budget ends the search early.
     */
    val unreportedProbesBeforeReorder: Int = 3,
    /**
     * How many times one candidate may be reopened: once to re-verify it as the winner, and once
     * more to recover it -- a source that worked moments earlier -- when a better-looking
     * alternative then fails to reopen or verifies lower. A candidate that keeps failing or
     * changing isn't pursued further, which also bounds the reopening loop.
     */
    val reopensPerCandidate: Int = 2,
    /**
     * How long, after the first usable (verified) candidate, further candidates may be started to
     * look for a better one. The search tries every candidate that could still improve on the best
     * found, best possible first ([CaptureQuality]), so the number of candidates is not capped --
     * this budget is. One candidate costs roughly 150-400 ms on a USB microphone (build, start,
     * route and device-side reads, stop, release), so 3 s covers some 8-20 of them: every
     * candidate that could still improve on a device advertising a handful of high-quality modes
     * (both audio sources), or the high-precision part of the exploratory set. Together with the
     * first candidate and a reopen, it keeps a start with a full search to about 4-5 s, well inside
     * [AudioStartup]'s startup watchdog. A search this budget cuts short is reported
     * [SearchCoverage.INCOMPLETE] ([SearchStop.SEARCH_BUDGET]), never as the maximum.
     */
    val searchBudgetMs: Long = 3_000,
    /** Time kept, when comparing candidates, to reopen and fully re-verify the winner: one route
     * window and one device-format window, with margin. Reopens (a recovery included) may use
     * whatever is left up to the overall deadline, never beyond it. */
    val reopenReserveMs: Long = 800,
    /**
     * The whole negotiation, all phases: however many candidates a device advertises, startup ends
     * by then. A normal startup takes well under a second (usually the first candidate is final);
     * this allows roughly ten external candidates to use their full route window before the
     * phone-microphone fallback, and stays well inside [AudioStartup]'s startup watchdog.
     */
    val totalMs: Long = 8_000,
    /** The part of [totalMs] kept for the phases after the first -- the phone-microphone fallback,
     * which normally succeeds on its first candidate -- so an external microphone that never
     * verifies can't use up all of it. */
    val fallbackReserveMs: Long = 2_000
)

/** Reads [read] until [settled] or [windowMs] (and [deadline]) run out, pausing [pollMs] between
 * reads through [scope] -- so the wait is in the scope's (injectable) time and ends as soon as the
 * startup is cancelled (by throwing [StartupCancelledException]). Returns the last value read. */
private fun <T> pollUntil(
    scope: StartupScope,
    windowMs: Long,
    pollMs: Long,
    deadline: Long,
    read: () -> T?,
    settled: (T?) -> Boolean
): T? {
    var value = read()
    val end = minOf(scope.nowMs() + windowMs, deadline)
    while (!settled(value)) {
        val remaining = end - scope.nowMs()
        if (remaining <= 0) break
        scope.pause(minOf(pollMs, remaining))
        value = read()
    }
    return value
}

/** One candidate going through [negotiateCapture]'s lifecycle, remembering how far it got so it is
 * cleaned up exactly as far as needed. */
private class CandidateTrial<C : CaptureCandidate>(val candidate: C) {
    /** Set just *before* startRecording(), so a start that throws partway still gets stopped. */
    var started = false
    var routed: InputDevice? = null
    var deviceFormat: DeviceSideFormat? = null
    private var discarded = false

    fun verify(
        attempt: NegotiationAttempt,
        target: RouteTarget,
        scope: StartupScope,
        limits: NegotiationLimits,
        deviceFormatWindowMs: Long,
        deadline: Long
    ): String? {
        scope.throwIfCancelled()
        candidate.clientFormatProblem(attempt.candidate.format)?.let { return it }
        target.requested?.let { device ->
            if (!candidate.requestDevice(device)) return "Android refused the request to route to ${device.label}"
        }
        started = true
        candidate.startRecording()
        if (!candidate.isRecording()) return "did not start recording"
        // This candidate's own settling window: polled until the route is the intended one, and
        // rejected if it is still absent or elsewhere when the window closes.
        val routedNow = pollUntil(
            scope,
            windowMs = if (target.requiresConfirmedRoute) limits.routeSettleMs else limits.optionalRouteSettleMs,
            pollMs = limits.pollMs,
            deadline = deadline,
            read = { candidate.routedDevice() },
            settled = { it != null && target.problemWith(it) == null }
        )
        target.problemWith(routedNow)?.let { return it }
        routed = routedNow
        // What Android reports the device side captures: the basis of the quality ranking, never a
        // reason to reject a candidate on its own.
        deviceFormat = try {
            pollUntil(scope, deviceFormatWindowMs, limits.pollMs, deadline, { candidate.deviceSideFormat() }, { it != null })
        } catch (e: StartupCancelledException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return null
    }

    /** Stops (if it was ever started) and releases, once; failures are ignored -- there is nothing
     * more to do with a candidate that won't release. */
    fun discard() {
        if (discarded) return
        discarded = true
        if (started) {
            try { candidate.stop() } catch (_: Exception) {}
        }
        try { candidate.release() } catch (_: Exception) {}
    }
}

/** A started, route-verified candidate and how it scores ([CaptureQuality]). */
private class Probe<C : CaptureCandidate>(val attempt: NegotiationAttempt, val trial: CandidateTrial<C>) {
    val score: CaptureQuality.Score = CaptureQuality.scoreOf(attempt, trial.deviceFormat)
    var running = true
        private set

    fun stop() {
        if (!running) return
        running = false
        trial.discard()
    }
}

/**
 * The quality search for one [CapturePhase] (see [negotiateCapture]): tries candidates one at a
 * time, keeps the best by [CaptureQuality], and ends with that one started and route-verified.
 *
 * Exploring (trying untried candidates) and settling (reopening the best one found) alternate in
 * one loop, and every reopen is a fresh observation: if a reopened candidate now scores lower, the
 * ranking and the search resume from what it scores *now*, and a candidate that verified moments
 * earlier stays available to fall back on.
 */
private class PhaseSearch<C : CaptureCandidate>(
    private val phase: CapturePhase,
    attempts: List<NegotiationAttempt>,
    private val audioSources: List<Int>,
    private val sdkInt: Int,
    private val open: (NegotiationAttempt) -> C,
    private val scope: StartupScope,
    private val limits: NegotiationLimits,
    /** No candidate is started from here on (and, once there is a best, from the reopen reserve before it). */
    private val phaseDeadline: Long,
    /** Reopens -- the winner's, or a recovery -- may happen until here. */
    private val finalDeadline: Long
) {
    val rejected = mutableListOf<RejectedAttempt>()
    var lastError: Throwable? = null
    var timedOut = false
    var report = SearchReport(SearchCoverage.NONE_USABLE, SearchStop.EXHAUSTED)
        private set

    /** One (format, source) that verified at least once: its latest successful verification. */
    private inner class Observation(val attempt: NegotiationAttempt, var probe: Probe<C>) {
        /** Its latest successful verification's score -- what it scores *now*, as far as is known. */
        val score: CaptureQuality.Score get() = probe.score
        var reopens = 0
        /** Its latest reopen failed (or it may not be reopened again): out of contention. */
        var unavailable = false
        /** [probe] came from a reopen. */
        var reopened = false
    }

    // In try order: best possible first, with each exact device-side format moved to the front.
    private val pending = ArrayList(attempts)
    private val tried = HashSet<Pair<PcmFormat, Int>>()
    private val observations = mutableListOf<Observation>()
    // Every verified instance, for cleanup should anything throw, and every verification, for diagnostics.
    private val probes = mutableListOf<Probe<C>>()
    private val verifications = mutableListOf<ObservedCandidate>()
    private var reportedAny = false
    private var unreportedInARow = 0
    private var opened = 0
    private var downgraded = 0
    // Set once there is a first usable candidate: no further candidate is started after it.
    private var searchDeadline = Long.MAX_VALUE
    private var searchDeadlineIsBudget = false
    // The candidate being opened/verified right now, so a cancellation can clean it up.
    private var inFlight: CandidateTrial<C>? = null

    private val NegotiationAttempt.key get() = candidate.format to audioSource

    /** The best candidate still available, by what it scores now (ties: the one verified first). */
    private val best: Observation? get() = observations.filter { !it.unavailable }.maxWithOrNull(compareBy { it.score })

    /** The one candidate that may be open, if any. */
    private val running: Observation? get() = observations.firstOrNull { it.probe.running }

    /** Several candidates in a row -- and none before them -- showed no device side. */
    private val reportsAbsent: Boolean
        get() = !reportedAny && unreportedInARow >= limits.unreportedProbesBeforeReorder

    /** The winner, still recording, or null when no candidate of this phase could be used. */
    fun run(): CaptureOutcome<C>? {
        try {
            while (true) {
                val stop = explore()
                val winner = best
                if (winner == null) {
                    report = SearchReport(SearchCoverage.NONE_USABLE, stop, attempts = opened, verified = observations.size)
                    timedOut = timedOut || stop == SearchStop.DEADLINE
                    return null
                }
                if (winner.probe.running) return finish(winner, stop)
                if (scope.nowMs() >= finalDeadline) {
                    // No time left to reopen the best: a candidate still running is kept rather
                    // than lost -- and reported as not the best found.
                    timedOut = true
                    val fallback = running ?: run { noneUsable(SearchStop.DEADLINE); return null }
                    return finish(fallback, SearchStop.DEADLINE)
                }
                if (winner.reopens >= limits.reopensPerCandidate) {
                    winner.unavailable = true
                    continue
                }
                // One microphone at a time: whatever is open (a fallback) is stopped first.
                running?.probe?.stop()
                reopen(winner)
            }
        } catch (e: Throwable) {
            // Cancellation (or anything unexpected): nothing this phase opened may stay open.
            inFlight?.discard()
            probes.forEach { it.stop() }
            throw e
        }
    }

    private fun noneUsable(stop: SearchStop) {
        report = SearchReport(SearchCoverage.NONE_USABLE, stop, attempts = opened, verified = observations.size)
    }

    /**
     * Starts untried candidates, one at a time, while one could still beat the best available
     * candidate and there is time; returns why it stopped. The best stays running if nothing
     * started after it beats it -- until the next candidate is started.
     */
    private fun explore(): SearchStop {
        while (true) {
            val current = best
            val next = nextImprover(current)
                ?: return if (pending.all { it.key in tried }) SearchStop.EXHAUSTED else SearchStop.NOTHING_BETTER_POSSIBLE
            if (scope.nowMs() >= (if (current == null) phaseDeadline else searchDeadline)) {
                return if (current != null && searchDeadlineIsBudget) SearchStop.SEARCH_BUDGET else SearchStop.DEADLINE
            }
            // One microphone at a time -- and a running candidate would also skew what Android
            // reports for the next one, which would share its input stream.
            running?.probe?.stop()
            val probe = probe(next, if (current == null) phaseDeadline else phaseDeadline - limits.reopenReserveMs) ?: continue
            val observation = Observation(next, probe)
            observations += observation
            record(probe, reopen = false)
            if (searchDeadline == Long.MAX_VALUE) {
                val budgetEnd = scope.nowMs() + limits.searchBudgetMs
                val hardEnd = phaseDeadline - limits.reopenReserveMs
                searchDeadline = minOf(budgetEnd, hardEnd)
                searchDeadlineIsBudget = budgetEnd < hardEnd
            }
            if (best !== observation) probe.stop()
        }
    }

    /** Reopens [observation] from scratch: success replaces what it's known to score, whatever
     * that now is; failure takes it out of contention. */
    private fun reopen(observation: Observation) {
        observation.reopens++
        val before = observation.score
        val probe = probe(observation.attempt, finalDeadline, reopening = true)
        if (probe == null) {
            observation.unavailable = true
            return
        }
        record(probe, reopen = true)
        if (probe.score < before) downgraded++
        observation.probe = probe
        observation.reopened = true
    }

    private fun record(probe: Probe<C>, reopen: Boolean) {
        probes += probe
        verifications += ObservedCandidate(probe.attempt, probe.trial.deviceFormat, probe.score.mode, reopen)
        if (probe.trial.deviceFormat != null) {
            reportedAny = true
            unreportedInARow = 0
        } else {
            unreportedInARow++
        }
        if (probe.score.mode == CaptureMode.CONVERTED) enqueueExact(probe.trial.deviceFormat!!)
    }

    /**
     * The next attempt worth starting: one not tried yet that could still beat [current] --
     * the first such in try order (best possible first, exact device-side formats first). Once
     * device-side reports have been absent for a while, whatever can win on the evidence at hand
     * goes first; that only changes the order, never what is tried.
     */
    private fun nextImprover(current: Observation?): NegotiationAttempt? {
        val improvers = pending.asSequence().filter { it.key !in tried && canImprove(it, current) }
        if (current != null && reportsAbsent) {
            improvers.filter { CaptureQuality.bestPossible(it, assumeUnreported = true) > current.score }
                .maxWithOrNull(compareBy { CaptureQuality.bestPossible(it, assumeUnreported = true) }) // first of equals
                ?.let { return it }
        }
        return improvers.firstOrNull()
    }

    private fun canImprove(attempt: NegotiationAttempt, current: Observation?): Boolean =
        current == null || CaptureQuality.bestPossible(attempt, assumeUnreported = false) > current.score

    /** The device side captured something other than what was asked: try asking for exactly that
     * next -- an exact path at the same credited quality -- when this app can record it. The same
     * format already pending under a more trusted origin keeps that origin. */
    private fun enqueueExact(device: DeviceSideFormat) {
        val exact = FormatNegotiation.exactCandidateFor(device, sdkInt) ?: return
        val candidate = pending.firstOrNull { it.candidate.format == exact.format && it.candidate.origin < exact.origin }?.candidate ?: exact
        audioSources.asReversed().forEach { source ->
            val attempt = NegotiationAttempt(source, candidate)
            if (attempt.key !in tried) pending.add(0, attempt)
        }
    }

    /** Opens, validates, starts and route-verifies [attempt], reading its device side; null (and
     * the attempt recorded as rejected, and cleaned up) when any step fails. */
    private fun probe(attempt: NegotiationAttempt, deadline: Long, reopening: Boolean = false): Probe<C>? {
        scope.throwIfCancelled()
        tried += attempt.key
        opened++
        val trial = try {
            CandidateTrial(open(attempt))
        } catch (e: StartupCancelledException) {
            throw e
        } catch (e: Exception) {
            reject(attempt, e.message ?: e.javaClass.simpleName, e, reopening)
            return null
        }
        inFlight = trial
        val deviceFormatWindow = if (observations.isEmpty() && !reopening) limits.firstDeviceFormatSettleMs else limits.deviceFormatSettleMs
        val problem = try {
            trial.verify(attempt, phase.target, scope, limits, deviceFormatWindow, deadline)
        } catch (e: StartupCancelledException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            e.message ?: e.javaClass.simpleName
        }
        inFlight = null
        if (problem != null) {
            trial.discard()
            reject(attempt, problem, null, reopening)
            return null
        }
        return Probe(attempt, trial)
    }

    private fun reject(attempt: NegotiationAttempt, reason: String, error: Exception?, reopening: Boolean) {
        if (error != null) lastError = error
        rejected += RejectedAttempt(attempt, if (reopening) "on reopening: $reason" else reason)
    }

    /**
     * Hands over [winner] -- running -- with the search judged against what it scores *now*:
     * incomplete if an untried candidate could still beat it (why: [stop]), or if another
     * candidate scored higher when last verified but couldn't be reopened.
     */
    private fun finish(winner: Observation, stop: SearchStop): CaptureOutcome<C> {
        val untried = pending.filter { it.key !in tried }.distinctBy { it.key }
        val improvers = untried.filter { canImprove(it, winner) }
        val higher = improvers.count { CaptureQuality.ceiling(it.candidate.format) > winner.score.level }
        val lostBetter = observations.any { it !== winner && it.score > winner.score }
        val (coverage, why) = when {
            lostBetter -> SearchCoverage.INCOMPLETE to SearchStop.BEST_NOT_REOPENED
            improvers.isNotEmpty() -> SearchCoverage.INCOMPLETE to
                (if (stop == SearchStop.SEARCH_BUDGET) SearchStop.SEARCH_BUDGET else SearchStop.DEADLINE)
            untried.isEmpty() -> SearchCoverage.COMPLETE to SearchStop.EXHAUSTED
            else -> SearchCoverage.COMPLETE to SearchStop.NOTHING_BETTER_POSSIBLE
        }
        report = SearchReport(coverage, why, improvers.size, higher, opened, observations.size, downgraded)
        return CaptureOutcome(
            winner.probe.trial.candidate, winner.attempt, phase.target, winner.probe.trial.routed, winner.probe.trial.deviceFormat,
            rejected.toList(), superseded = observations.count { it !== winner }, reopened = winner.reopened,
            search = report, observed = verifications.toList()
        )
    }
}

/**
 * Finds, for each phase in order, the candidate that scores best by [CaptureQuality] -- judged by
 * what Android reports the *device side* captures, not merely by the client format AudioRecord
 * agrees to deliver -- and returns it started and route-verified.
 *
 * Every candidate goes through the same lifecycle, one at a time:
 *  1. [open] builds it (a failure here leaves nothing to release);
 *  2. its client format must be exactly the requested one;
 *  3. the phase's device is requested via setPreferredDevice, and a refusal rejects it;
 *  4. it's started, and must actually be recording;
 *  5. within its own settling window ([NegotiationLimits.routeSettleMs]), the device it's actually
 *     routed to must come to satisfy the phase's [RouteTarget];
 *  6. the device-side format is read (`AudioRecordingConfiguration.getFormat()`).
 * That AudioRecord initializes and starts in a format shows only that Android can deliver it; how
 * it is credited depends on step 6 ([CaptureQuality]).
 *
 * The search, per phase -- coverage-aware rather than capped at a number of candidates:
 *  - Candidates are tried best possible first ([CaptureQuality.probeOrder]; each format with
 *    UNPROCESSED, then MIC). A verified candidate is scored by what Android reports it captures.
 *  - The next candidate tried is the first untried one that could still score higher than the
 *    best available so far ([CaptureQuality.bestPossible]); exploring ends when there is none
 *    ([SearchCoverage.COMPLETE]), so a matched result at the top of the order is final at once.
 *  - A converted result puts the exact client format for the device side Android reported at the
 *    front (when this app can record it); equivalent attempts -- the same format and source from
 *    several origins -- are tried once.
 *  - Device-side reporting is judged per candidate. Missing reports never remove a candidate:
 *    after [NegotiationLimits.unreportedProbesBeforeReorder] in a row (and none reported), the
 *    candidate that can win on the evidence at hand is simply tried first.
 *  - Only one candidate is ever open: the best so far is stopped before the next is tried. The
 *    winner, if it had to be stopped, is reopened and every check above is repeated -- never
 *    reusing an earlier route or device-side result. A reopen is a fresh observation: if the
 *    candidate now scores lower, the ranking uses what it scores now, exploring resumes (within
 *    the time budget) for anything that could beat that, and the next best is reopened instead
 *    if it is now better. A candidate that failed to reopen is dropped; one that reopened lower
 *    stays available, and may be reopened once more to recover it
 *    ([NegotiationLimits.reopensPerCandidate]).
 *  - Time: once there is a usable candidate, new candidates are started only within
 *    [NegotiationLimits.searchBudgetMs] (and before the reopen reserve); reopens may use the time
 *    up to the overall [NegotiationLimits.totalMs]. If that runs out while a candidate is running,
 *    it is returned rather than lost.
 *  - The result's [SearchReport] is judged against what the returned capture scores: incomplete
 *    when a candidate that could beat it went untried, or when one that scored higher couldn't be
 *    reopened ([SearchStop.BEST_NOT_REOPENED]).
 *  - If no exact path works, the best converted candidate is used (reported as converted); if the
 *    device side is never reported, the ranking of the client formats decides (reported as
 *    unreported).
 *
 * Bounded: every (format, source) is started at most once, each reopened at most
 * [NegotiationLimits.reopensPerCandidate] times, and every wait ends by the deadlines.
 *
 * Every candidate that loses, fails or is superseded is stopped (if it was started) and released
 * exactly once; the winner is returned still recording, so the route that was verified is the
 * route it captures on.
 *
 * Runs on the audio worker (see [AudioStartup]), only at startup: a session's format never changes
 * once it is recording. [scope] supplies the time every wait uses and its cancellation: once
 * cancelled, whatever is open is stopped and released and [StartupCancelledException] is thrown,
 * with nothing further attempted. A phase starts no new candidate after its deadline (the overall
 * [NegotiationLimits.totalMs], less [NegotiationLimits.fallbackReserveMs] for any phase that has
 * another after it). Throws [FormatNegotiationException] when no phase found a usable candidate.
 */
internal fun <C : CaptureCandidate> negotiateCapture(
    phases: List<CapturePhase>,
    audioSources: List<Int>,
    sdkInt: Int,
    open: (NegotiationAttempt) -> C,
    scope: StartupScope,
    limits: NegotiationLimits = NegotiationLimits()
): CaptureOutcome<C> {
    val rejected = mutableListOf<RejectedAttempt>()
    var lastError: Throwable? = null
    var timedOut = false
    val deadline = scope.nowMs() + limits.totalMs
    for ((index, phase) in phases.withIndex()) {
        scope.throwIfCancelled()
        val phaseDeadline = if (index < phases.lastIndex) deadline - limits.fallbackReserveMs else deadline
        val attempts = FormatNegotiation.attemptOrder(FormatNegotiation.candidates(phase.capabilities, sdkInt), audioSources)
        val search = PhaseSearch(phase, attempts, audioSources, sdkInt, open, scope, limits, phaseDeadline, deadline)
        val outcome = search.run()
        if (outcome != null) {
            if (scope.isCancelled) {
                // Cancelled at the very moment it was accepted: still ours to clean up.
                outcome.candidate.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
                throw StartupCancelledException()
            }
            return CaptureOutcome(
                outcome.candidate, outcome.attempt, outcome.target, outcome.routed, outcome.deviceFormat,
                rejected + outcome.rejected, outcome.superseded, outcome.reopened, outcome.search, outcome.observed
            )
        }
        rejected += search.rejected
        lastError = search.lastError ?: lastError
        timedOut = timedOut || search.timedOut || search.report.stop == SearchStop.DEADLINE
    }
    throw FormatNegotiationException(
        "No usable microphone format: all ${rejected.size} attempts failed" +
            (if (timedOut) ", and the ${limits.totalMs / 1000} s negotiation time limit ended the rest" else "") +
            (rejected.lastOrNull()?.reason?.let { " (last: $it)" } ?: ""),
        lastError,
        rejected,
        timedOut
    )
}

/**
 * The [AudioSource] for a candidate [negotiateCapture] accepted: already recording on its verified
 * route, which it keeps watching -- the removal callback (via [removalWatcher]) and the routed
 * device itself -- exactly as recording and the microphone test expect. Reads go through the
 * candidate, i.e. the one production read path ([DirectPcmReader]).
 */
internal class SystemAudioSource(
    private val candidate: CaptureCandidate,
    /** The device verified as routed at negotiation; null when Android couldn't say. */
    private val verifiedRoute: InputDevice?,
    private val deviceFormat: DeviceSideFormat?,
    removalWatcher: DeviceRemovalWatcher?
) : AudioSource {
    @Volatile private var deviceConnected = true

    // Only a verified *external* device is watched: the built-in mic can't "disconnect", and
    // without a verified route there's nothing concrete to match a removal against. Registered here
    // -- for the accepted candidate only -- so no rejected candidate ever leaves a callback behind.
    private var removalWatch: AutoCloseable? =
        if (verifiedRoute != null && verifiedRoute.isExternal && removalWatcher != null) {
            removalWatcher.watch(verifiedRoute.id) { deviceConnected = false }
        } else {
            null
        }

    /** Never valid: negotiation already started this source and verified where it's routed.
     * Starting it again could re-route it without that check. */
    override fun startRecording() {
        throw IllegalStateException("Already started and route-verified during negotiation; must not be started again")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = candidate.read(buffer, offset, length)

    override fun stop() = candidate.stop()

    override fun release() {
        val watch = removalWatch
        removalWatch = null
        releaseAudioSourceSafely(
            unregisterCallback = { watch?.close() },
            releaseSource = { candidate.release() }
        )
    }

    override fun describeMicrophone(): MicrophoneInfo = verifiedRoute?.let {
        MicrophoneInfo(label = it.label, isExternal = it.isExternal, verified = true)
    } ?: MicrophoneInfo.UNVERIFIED

    override fun deviceSideFormat(): DeviceSideFormat? = deviceFormat

    override fun isDeviceConnected(): Boolean = deviceConnected

    // Independent of the removal callback above: this re-checks the live routing directly, so a
    // reroute the OS never reports as a "removal" at all (most commonly a silent fallback to the
    // built-in mic while the external device is still attached) is still caught.
    override fun isRouteUnchanged(): Boolean {
        val verified = verifiedRoute ?: return true
        val routed = candidate.routedDevice() ?: return false
        if (routed.id != verified.id) return false
        if (verified.isExternal && !routed.isExternal) return false
        return true
    }
}

private const val NEGOTIATION_TAG = "AudioNegotiation"

/** Most verified/rejected attempts listed in one log entry. */
private const val MAX_LOGGED_ATTEMPTS = 24

/** UNPROCESSED first, then MIC, for each format (see [FormatNegotiation.attemptOrder]). */
internal val CAPTURE_AUDIO_SOURCES = listOf(NegotiatedAudio.AUDIO_SOURCE_UNPROCESSED, NegotiatedAudio.AUDIO_SOURCE_MIC)

/**
 * Negotiates [phases] and hands back the accepted candidate as a started, route-verified
 * [SystemAudioSource] ([WavRecorder.RecorderConfig.alreadyStarted]). If wrapping it fails (the
 * removal watch can't be registered), the candidate is stopped and released before rethrowing, so
 * a failure here never leaves the microphone running.
 */
internal fun openNegotiatedCapture(
    phases: List<CapturePhase>,
    sdkInt: Int,
    unprocessedSupported: Boolean,
    openCandidate: (NegotiationAttempt) -> CaptureCandidate,
    removalWatcher: DeviceRemovalWatcher?,
    scope: StartupScope,
    limits: NegotiationLimits = NegotiationLimits()
): WavRecorder.RecorderConfig {
    val startedAt = scope.nowMs()
    val outcome = try {
        negotiateCapture(phases, CAPTURE_AUDIO_SOURCES, sdkInt, openCandidate, scope, limits)
    } catch (e: FormatNegotiationException) {
        Log.w(
            NEGOTIATION_TAG,
            "No usable format after ${scope.nowMs() - startedAt} ms: ${e.message}" +
                phases.joinToString("") { "\n  capabilities of ${it.target}: ${it.capabilities.describe()}" } +
                e.rejected.take(MAX_LOGGED_ATTEMPTS).joinToString("") {
                    "\n  rejected ${it.attempt.candidate.format} (${it.attempt.candidate.origin}, source ${it.attempt.audioSource}): ${it.reason}"
                }
        )
        throw e
    }
    val format = outcome.attempt.candidate.format
    val negotiation = NegotiatedAudio(
        format = format,
        origin = outcome.attempt.candidate.origin,
        audioSource = outcome.attempt.audioSource,
        unprocessedSupported = unprocessedSupported,
        rejectedAttempts = outcome.rejected.size,
        captureMode = outcome.mode,
        deviceFormat = outcome.deviceFormat,
        supersededCandidates = outcome.superseded,
        reopened = outcome.reopened,
        search = outcome.search,
        rejectedAdvertisedAttempts = outcome.rejected.count {
            it.attempt.candidate.origin == CandidateOrigin.PROFILE || it.attempt.candidate.origin == CandidateOrigin.CAPABILITY_ARRAYS
        },
        negotiationMs = scope.nowMs() - startedAt
    )
    // Everything hardware validation needs, in one entry: what the device advertised, what was
    // tried and verified, what Android reported for each, and why the search ended.
    Log.i(
        NEGOTIATION_TAG,
        "Negotiated in ${negotiation.negotiationMs} ms: saving $format (client/file format; ${negotiation.origin}) from " +
            "${outcome.routed?.let { "${it.label} (id ${it.id})" } ?: "an unreported input"}, target ${outcome.target}" +
            "\n  device side (AudioRecordingConfiguration.getFormat): ${outcome.deviceFormat ?: "not reported"} -> ${outcome.mode}" +
            "\n  processing: ${negotiation.processing}" +
            "\n  search: ${outcome.search}; ${outcome.superseded} other verified candidate(s) scored lower; reopened: ${outcome.reopened}" +
            phases.joinToString("") { "\n  capabilities of ${it.target}: ${it.capabilities.describe()}" } +
            outcome.observed.take(MAX_LOGGED_ATTEMPTS).joinToString("") {
                "\n  ${if (it.reopen) "reopened" else "verified"} ${it.attempt.candidate.format} (${it.attempt.candidate.origin}, " +
                    "source ${it.attempt.audioSource}): device side ${it.deviceFormat ?: "not reported"} -> ${it.mode}"
            } +
            outcome.rejected.take(MAX_LOGGED_ATTEMPTS).joinToString("") {
                "\n  rejected ${it.attempt.candidate.format} (${it.attempt.candidate.origin}, source ${it.attempt.audioSource}): ${it.reason}"
            }
    )
    val source = try {
        SystemAudioSource(outcome.candidate, outcome.routed, outcome.deviceFormat, removalWatcher)
    } catch (e: Exception) {
        try { outcome.candidate.stop() } catch (_: Exception) {}
        try { outcome.candidate.release() } catch (_: Exception) {}
        throw e
    }
    return WavRecorder.RecorderConfig(source, format, outcome.candidate.bufferSize, negotiation, alreadyStarted = true)
}
