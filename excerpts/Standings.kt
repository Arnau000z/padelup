// Excerpt from PadelUp (not the complete source).
// (c) 2026 Arnau Mas Sánchez, all rights reserved. Shown for portfolio purposes only.
package com.padelup.app.engine

import com.padelup.app.model.Standing
import com.padelup.app.model.Tiebreak
import com.padelup.app.model.Tournament

/** Computes the live individual ranking from all played games. */
object StandingsEngine {

    fun compute(t: Tournament): List<Standing> {
        val points = HashMap<Int, Int>()
        val conceded = HashMap<Int, Int>()
        val played = HashMap<Int, Int>()
        val won = HashMap<Int, Int>()
        t.players.forEach {
            points[it.id] = 0; conceded[it.id] = 0; played[it.id] = 0; won[it.id] = 0
        }

        for (round in t.rounds) {
            for (g in round.games) {
                val sa = g.scoreA ?: continue
                val sb = g.scoreB ?: continue
                for (pid in intArrayOf(g.a.p1, g.a.p2)) {
                    points[pid] = (points[pid] ?: 0) + sa
                    conceded[pid] = (conceded[pid] ?: 0) + sb
                    played[pid] = (played[pid] ?: 0) + 1
                    if (sa > sb) won[pid] = (won[pid] ?: 0) + 1
                }
                for (pid in intArrayOf(g.b.p1, g.b.p2)) {
                    points[pid] = (points[pid] ?: 0) + sb
                    conceded[pid] = (conceded[pid] ?: 0) + sa
                    played[pid] = (played[pid] ?: 0) + 1
                    if (sb > sa) won[pid] = (won[pid] ?: 0) + 1
                }
            }
        }

        val list = t.players.map { p ->
            Standing(
                playerId = p.id,
                name = p.name,
                points = points[p.id] ?: 0,
                conceded = conceded[p.id] ?: 0,
                played = played[p.id] ?: 0,
                won = won[p.id] ?: 0,
            )
        }

        val comparator = when (t.config.tiebreak) {
            Tiebreak.POINT_DIFF ->
                compareByDescending<Standing> { it.points }
                    .thenByDescending { it.diff }
                    .thenByDescending { it.won }
                    .thenBy { it.name.lowercase() }
            Tiebreak.GAMES_WON ->
                compareByDescending<Standing> { it.points }
                    .thenByDescending { it.won }
                    .thenByDescending { it.diff }
                    .thenBy { it.name.lowercase() }
        }
        return list.sortedWith(comparator)
    }

    /**
     * Current standings paired with each player's position change versus the
     * standings BEFORE the most recently played round (+ = moved up, - = down).
     */
    fun computeWithDelta(t: Tournament): List<Pair<Standing, Int>> {
        val current = compute(t)
        val lastPlayed = t.rounds.indexOfLast { r -> r.games.any { g -> g.played } }
        val prevOrder: List<Int> = if (lastPlayed <= 0) {
            current.map { it.playerId }
        } else {
            val prevT = t.copy(rounds = t.rounds.filterIndexed { i, _ -> i != lastPlayed })
            compute(prevT).map { it.playerId }
        }
        val prevPos = prevOrder.withIndex().associate { (i, id) -> id to i }
        return current.mapIndexed { i, s ->
            val delta = (prevPos[s.playerId] ?: i) - i
            s to delta
        }
    }

    /** How many games each player has played so far (for fair rest rotation). */
    fun gamesPlayedById(t: Tournament): Map<Int, Int> {
        val played = HashMap<Int, Int>()
        t.players.forEach { played[it.id] = 0 }
        for (round in t.rounds) {
            for (g in round.games) {
                for (pid in intArrayOf(g.a.p1, g.a.p2, g.b.p1, g.b.p2)) {
                    played[pid] = (played[pid] ?: 0) + 1
                }
            }
        }
        return played
    }
}
