package com.animesh.fitnesstracker.domain.health

import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.Session
import com.animesh.fitnesstracker.data.model.SessionStatus

/**
 * Links a watch activity to a logged app session (FR52): the two are linked when they overlap by
 * at least half of the session's duration. Sessions are in Unix millis, activities in Unix seconds.
 */
object SessionMatcher {
    /** Seconds the activity and the session share, 0 when they do not touch or the session is unfinished. */
    fun overlapSeconds(activity: Activity, session: Session): Long {
        val end = session.endedAt ?: return 0
        val sessionStart = session.startedAt / 1000
        val sessionEnd = end / 1000
        val overlap = minOf(activity.endTimestamp, sessionEnd) - maxOf(activity.startTimestamp, sessionStart)
        return overlap.coerceAtLeast(0)
    }

    /** True when the overlap covers at least half of the session. Unfinished or abandoned sessions never match. */
    fun matches(activity: Activity, session: Session): Boolean {
        if (session.status != SessionStatus.COMPLETED) return false
        val end = session.endedAt ?: return false
        val duration = (end - session.startedAt) / 1000
        if (duration <= 0) return false
        return overlapSeconds(activity, session) * 2 >= duration
    }

    /** The matching session with the largest overlap, or null. */
    fun match(activity: Activity, sessions: List<Session>): Session? =
        sessions.filter { matches(activity, it) }.maxByOrNull { overlapSeconds(activity, it) }

    /**
     * Pairs activities with sessions, each used at most once, preferring the largest overlaps.
     * Activities already linked to a session are skipped.
     */
    fun linkActivities(activities: List<Activity>, sessions: List<Session>): List<Pair<Activity, Session>> {
        val candidates = ArrayList<Triple<Activity, Session, Long>>()
        for (a in activities) {
            if (a.linkedSessionId != null) continue
            for (s in sessions) {
                if (matches(a, s)) candidates += Triple(a, s, overlapSeconds(a, s))
            }
        }
        candidates.sortByDescending { it.third }
        val usedActivities = HashSet<Long>()
        val usedSessions = HashSet<Long>()
        val result = ArrayList<Pair<Activity, Session>>()
        for ((a, s, _) in candidates) {
            // Activities without an id yet (not inserted) are keyed by start time so tests can use them too.
            val activityKey = if (a.id != 0L) a.id else -a.startTimestamp
            if (activityKey in usedActivities || s.id in usedSessions) continue
            usedActivities += activityKey
            usedSessions += s.id
            result += a to s
        }
        return result
    }
}
