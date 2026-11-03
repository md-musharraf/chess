package com.gambit.chess.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun perft(p: Position, depth: Int): Long {
        if (depth == 0) return 1
        val buf = IntArray(256)
        val n = p.generate(buf)
        var total = 0L
        for (i in 0 until n) {
            p.make(buf[i])
            if (!p.leftKingInCheck()) total += perft(p, depth - 1)
            p.unmake()
        }
        return total
    }

    private fun assertPerft(fen: String, vararg expected: Long) {
        val p = Position.fromFen(fen)
        val hash = p.hash
        expected.forEachIndexed { i, nodes -> assertEquals("$fen depth ${i + 1}", nodes, perft(p, i + 1)) }
        assertEquals("make/unmake must restore the hash", hash, p.hash)
    }

    // Reference counts from chessprogramming.org/Perft_Results.
    @Test fun perftStart() = assertPerft(Position.START_FEN, 20, 400, 8902, 197281)
    @Test fun perftKiwipete() = assertPerft("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 48, 2039, 97862)
    @Test fun perftEndgame() = assertPerft("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 14, 191, 2812, 43238)
    @Test fun perftPromotions() = assertPerft("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 6, 264, 9467)
    @Test fun perftPosition5() = assertPerft("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 44, 1486, 62379)

    private fun game(vararg uci: String) = Game().apply { uci.forEach { assertTrue("illegal $it", playUci(it)) } }

    @Test fun foolsMate() {
        val g = game("f2f3", "e7e5", "g2g4", "d8h4")
        assertEquals(listOf("f3", "e5", "g4", "Qh4#"), g.sanMoves)
        assertEquals(Outcome(BLACK, "checkmate"), g.outcome)
        assertEquals(0, g.legalMoves().size)
    }

    @Test fun sanCastlingEnPassantPromotion() {
        val g = game("e2e4", "g8f6", "e4e5", "d7d5", "e5d6", "e7e6", "g1f3", "f8e7", "f1e2", "e8g8", "d6c7", "b8c6", "c7d8q")
        assertEquals(listOf("e4", "Nf6", "e5", "d5", "exd6", "e6", "Nf3", "Be7", "Be2", "O-O", "dxc7", "Nc6", "cxd8=Q"), g.sanMoves)
        g.undo()
        assertEquals(12, g.moves.size)
        assertEquals(PAWN, g.position.board[8 * 6 + 2]) // pawn back on c7
    }

    @Test fun disambiguation() {
        val g = game("g1f3", "a7a6", "b1c3", "a6a5", "f3d4", "a5a4", "c3e4", "a4a3")
        assertEquals("Nb5", g.san(g.legalMoves().first { uci(it) == "d4b5" }))
        assertTrue(g.playUci("e4c5") && g.playUci("h7h6"))
        assertEquals("Ncb3", g.san(g.legalMoves().first { uci(it) == "c5b3" }))
        assertEquals("Ndb3", g.san(g.legalMoves().first { uci(it) == "d4b3" }))
    }

    @Test fun threefoldRepetition() {
        val g = game("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1")
        assertNull(g.outcome)
        assertTrue(g.playUci("f6g8"))
        assertEquals(Outcome(0, "threefold repetition"), g.outcome)
    }

    @Test fun insufficientMaterial() {
        assertTrue(Position.fromFen("8/8/4k3/8/8/2B5/4K3/8 w - - 0 1").insufficientMaterial())
        assertTrue(Position.fromFen("8/8/4k3/4b3/8/2B5/4K3/8 w - - 0 1").insufficientMaterial()) // same-coloured bishops
        assertTrue(!Position.fromFen("8/8/4k3/3b4/8/2B5/4K3/8 w - - 0 1").insufficientMaterial())
        assertTrue(!Position.fromFen("8/8/4k3/8/8/2N5/4KN2/8 w - - 0 1").insufficientMaterial())
    }

    @Test fun engineFindsMateInOneAndFreeQueen() {
        val mate = Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1")
        for (level in Level.entries) assertEquals(level.name, "d1d8", uci(Engine().think(mate, level)))
        val queen = Position.fromFen("4k3/8/8/3q4/8/8/3R4/4K3 w - - 0 1")
        assertEquals("d2d5", uci(Engine().think(queen, Level.ADVANCED)))
    }

    @Test fun engineSearchLeavesPositionUntouched() {
        val g = game("e2e4", "e7e5")
        val before = g.position.board.copyOf()
        Engine().think(g.position, Level.ADVANCED)
        assertTrue(before.contentEquals(g.position.board))
    }
}
