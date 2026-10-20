// Excerpt from PadelUp (not the complete source).
// (c) 2026 Arnau Mas Sánchez, all rights reserved. Shown for portfolio purposes only.
package com.padelup.app.engine

import com.padelup.app.model.Game
import com.padelup.app.model.Player
import com.padelup.app.model.Round
import com.padelup.app.model.Standing
import com.padelup.app.model.Team
import kotlin.random.Random

/**
 * Generates the schedule.
 *
 * AMERICANO: a fixed schedule is built up front. Each round we pick the players
 * who have played the fewest games (fair rest), then form pairs and matchups that
 * minimise repeated partners and opponents, using a randomised greedy search.
 *
 * MEXICANO: rounds are generated one at a time from the live standings, players
 * are ranked, grouped by 4, and paired best+worst vs 2nd+3rd to balance each game.
 */
object Pairing {

    /** Courts actually usable given the player count (each court needs 4 players). */
    fun courtsUsed(players: Int, courts: Int): Int = minOf(courts, players / 4)

    /** A sensible default round count for a full "everyone partners everyone" americano. */
    fun suggestedRounds(players: Int): Int = (players - 1).coerceIn(1, 15)

    fun americano(players: List<Player>, courts: Int, rounds: Int, seed: Long = 7L): List<Round> {
        val n = players.size
        val cU = courtsUsed(n, courts)
        if (cU < 1 || rounds < 1) return emptyList()
        val perRound = cU * 4
        val rng = Random(seed)

        val partner = Array(n) { IntArray(n) }
        val opponent = Array(n) { IntArray(n) }
        val games = IntArray(n)

        val result = ArrayList<Round>(rounds)
        for (r in 0 until rounds) {
            // Players with the fewest games so far play; the rest sit out this round.
            // Shuffle first, then a STABLE sort by games: this randomises the tie-break
            // once per element (a per-comparison rng would make the comparator
            // inconsistent and crash TimSort at 32+ players).
            val order = (0 until n).shuffled(rng).sortedBy { games[it] }
            val playing = order.take(perRound)
            val resting = order.drop(perRound)

            val matches = bestRoundAssignment(playing, partner, opponent, rng)

            for ((pa, pb) in matches) {
                partner[pa.first][pa.second]++; partner[pa.second][pa.first]++
                partner[pb.first][pb.second]++; partner[pb.second][pb.first]++
                for (x in intArrayOf(pa.first, pa.second)) {
                    for (y in intArrayOf(pb.first, pb.second)) {
                        opponent[x][y]++; opponent[y][x]++
                    }
                }
                for (p in intArrayOf(pa.first, pa.second, pb.first, pb.second)) games[p]++
            }

            val gameList = matches.mapIndexed { i, (pa, pb) ->
                Game(
                    court = i + 1,
                    a = Team(players[pa.first].id, players[pa.second].id),
                    b = Team(players[pb.first].id, players[pb.second].id),
                )
            }
            result.add(Round(index = r, games = gameList, resting = resting.map { players[it].id }))
        }
        return result
    }

    fun mexicanoFirstRound(players: List<Player>, courts: Int, seed: Long = 7L): Round =
        americano(players, courts, rounds = 1, seed = seed).firstOrNull()
            ?: Round(0, emptyList(), players.map { it.id })

    fun mexicanoNextRound(
        players: List<Player>,
        courts: Int,
        roundIndex: Int,
        standings: List<Standing>, // already sorted best-first
        gamesPlayed: Map<Int, Int>,
    ): Round {
        val n = players.size
        val cU = courtsUsed(n, courts)
        if (cU < 1) return Round(roundIndex, emptyList(), players.map { it.id })
        val perRound = cU * 4

        // Those who have played the most rest first (fair rotation), worst-ranked among ties.
        val rankIndex = standings.withIndex().associate { (i, s) -> s.playerId to i }
        val resting = players.map { it.id }
            .sortedWith(
                compareByDescending<Int> { gamesPlayed[it] ?: 0 }
                    .thenByDescending { rankIndex[it] ?: Int.MAX_VALUE }
            )
            .take(n - perRound)
            .toSet()

        // Keep standings order among the players who actually play.
        val playing = standings.map { it.playerId }.filter { it !in resting }

        val gameList = ArrayList<Game>()
        var court = 1
        var i = 0
        while (i + 4 <= playing.size) {
            val w = playing[i]; val x = playing[i + 1]; val y = playing[i + 2]; val z = playing[i + 3]
            gameList.add(Game(court = court, a = Team(w, z), b = Team(x, y))) // best+worst vs 2nd+3rd
            court++
            i += 4
        }
        return Round(roundIndex, gameList, resting.toList())
    }

    // --- internal greedy search -------------------------------------------------

    private fun bestRoundAssignment(
        playing: List<Int>,
        partner: Array<IntArray>,
        opponent: Array<IntArray>,
        rng: Random,
        trials: Int = 160,
    ): List<Pair<Pair<Int, Int>, Pair<Int, Int>>> {
        var best: List<Pair<Pair<Int, Int>, Pair<Int, Int>>> = emptyList()
        var bestCost = Int.MAX_VALUE
        repeat(trials) {
            val shuffled = playing.shuffled(rng)
            val avail = shuffled.toMutableList()
            val pairs = ArrayList<Pair<Int, Int>>()
            while (avail.size >= 2) {
                val x = avail.removeAt(0)
                val y = avail.minByOrNull { partner[x][it] } ?: break
                avail.remove(y)
                pairs.add(x to y)
            }
            val pool = pairs.toMutableList()
            val matches = ArrayList<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
            while (pool.size >= 2) {
                val pa = pool.removeAt(0)
                val pb = pool.minByOrNull { opponentCost(pa, it, opponent) } ?: break
                pool.remove(pb)
                matches.add(pa to pb)
            }
            val cost = roundCost(matches, partner, opponent)
            if (cost < bestCost) {
                bestCost = cost
                best = matches
            }
        }
        return best
    }

    private fun opponentCost(
        pa: Pair<Int, Int>,
        pb: Pair<Int, Int>,
        opponent: Array<IntArray>,
    ): Int {
        var c = 0
        for (x in intArrayOf(pa.first, pa.second)) {
            for (y in intArrayOf(pb.first, pb.second)) c += opponent[x][y]
        }
        return c
    }

    private fun roundCost(
        matches: List<Pair<Pair<Int, Int>, Pair<Int, Int>>>,
        partner: Array<IntArray>,
        opponent: Array<IntArray>,
    ): Int {
        var c = 0
        for ((pa, pb) in matches) {
            c += partner[pa.first][pa.second] * 3 // repeating a partner is worse than repeating a rival
            c += partner[pb.first][pb.second] * 3
            c += opponentCost(pa, pb, opponent)
        }
        return c
    }
}
