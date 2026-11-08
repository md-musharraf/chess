package com.gambit.chess.core

data class OpeningInfo(val name: String, val eco: String = "")

object OpeningBook {
    private val openings = mapOf(
        // 1. e4
        "e2e4" to OpeningInfo("King's Pawn Opening", "B00"),
        "e2e4 e7e5" to OpeningInfo("Open Game", "C20"),
        "e2e4 e7e5 g1f3" to OpeningInfo("King's Knight Opening", "C40"),
        "e2e4 e7e5 g1f3 b8c6" to OpeningInfo("King's Knight: Normal", "C44"),
        "e2e4 e7e5 g1f3 b8c6 f1b5" to OpeningInfo("Ruy Lopez", "C60"),
        "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6" to OpeningInfo("Ruy Lopez: Morphy Defense", "C70"),
        "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6" to OpeningInfo("Ruy Lopez: Closed", "C84"),
        "e2e4 e7e5 g1f3 b8c6 f1b5 g8f6" to OpeningInfo("Ruy Lopez: Berlin Defense", "C65"),
        "e2e4 e7e5 g1f3 b8c6 f1b5 f7f5" to OpeningInfo("Ruy Lopez: Schliemann Defense", "C63"),
        "e2e4 e7e5 g1f3 b8c6 f1c4" to OpeningInfo("Italian Game", "C50"),
        "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5" to OpeningInfo("Giuoco Piano", "C50"),
        "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 c2c3" to OpeningInfo("Giuoco Piano: Main Line", "C53"),
        "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 b2b4" to OpeningInfo("Evans Gambit", "C51"),
        "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6" to OpeningInfo("Two Knights Defense", "C55"),
        "e2e4 e7e5 g1f3 b8c6 d2d4" to OpeningInfo("Scotch Game", "C45"),
        "e2e4 e7e5 g1f3 b8c6 b1c3" to OpeningInfo("Three Knights Opening", "C46"),
        "e2e4 e7e5 g1f3 b8c6 b1c3 g8f6" to OpeningInfo("Four Knights Game", "C47"),
        "e2e4 e7e5 g1f3 d7d6" to OpeningInfo("Philidor Defense", "C41"),
        "e2e4 e7e5 g1f3 g8f6" to OpeningInfo("Petrov's Defense", "C42"),
        "e2e4 e7e5 f2f4" to OpeningInfo("King's Gambit", "C30"),
        "e2e4 e7e5 f2f4 e5f4" to OpeningInfo("King's Gambit Accepted", "C33"),
        "e2e4 e7e5 d2d4" to OpeningInfo("Center Game", "C21"),
        "e2e4 e7e5 b1c3" to OpeningInfo("Vienna Game", "C25"),

        // Sicilian
        "e2e4 c7c5" to OpeningInfo("Sicilian Defense", "B20"),
        "e2e4 c7c5 g1f3" to OpeningInfo("Sicilian: Open", "B27"),
        "e2e4 c7c5 g1f3 d7d6" to OpeningInfo("Sicilian: Modern", "B50"),
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3 a7a6" to OpeningInfo("Sicilian: Najdorf", "B90"),
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3 g7g6" to OpeningInfo("Sicilian: Dragon", "B70"),
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3 e7e6" to OpeningInfo("Sicilian: Scheveningen", "B80"),
        "e2e4 c7c5 g1f3 b8c6" to OpeningInfo("Sicilian: Old Sicilian", "B30"),
        "e2e4 c7c5 g1f3 b8c6 f1b5" to OpeningInfo("Sicilian: Rossolimo", "B51"),
        "e2e4 c7c5 g1f3 e7e6" to OpeningInfo("Sicilian: French Variation", "B40"),
        "e2e4 c7c5 c2c3" to OpeningInfo("Sicilian: Alapin", "B22"),
        "e2e4 c7c5 b1c3" to OpeningInfo("Sicilian: Closed", "B23"),
        "e2e4 c7c5 d2d4 c5d4 c2c3" to OpeningInfo("Smith-Morra Gambit", "B21"),

        // French & Caro-Kann
        "e2e4 e7e6" to OpeningInfo("French Defense", "C00"),
        "e2e4 e7e6 d2d4 d7d5" to OpeningInfo("French Defense: Main", "C01"),
        "e2e4 e7e6 d2d4 d7d5 e4e5" to OpeningInfo("French Defense: Advance", "C02"),
        "e2e4 e7e6 d2d4 d7d5 e4d5" to OpeningInfo("French Defense: Exchange", "C01"),
        "e2e4 e7e6 d2d4 d7d5 b1c3" to OpeningInfo("French Defense: Paulsen", "C10"),
        "e2e4 e7e6 d2d4 d7d5 b1d2" to OpeningInfo("French Defense: Tarrasch", "C03"),
        "e2e4 c7c6" to OpeningInfo("Caro-Kann Defense", "B10"),
        "e2e4 c7c6 d2d4 d7d5" to OpeningInfo("Caro-Kann Defense: Main", "B12"),
        "e2e4 c7c6 d2d4 d7d5 e4e5" to OpeningInfo("Caro-Kann: Advance", "B12"),
        "e2e4 c7c6 d2d4 d7d5 b1c3 d5e4 c3e4" to OpeningInfo("Caro-Kann: Classical", "B18"),
        "e2e4 c7c6 d2d4 d7d5 e4d5 c6d5" to OpeningInfo("Caro-Kann: Exchange", "B13"),

        // Other 1. e4
        "e2e4 d7d5" to OpeningInfo("Scandinavian Defense", "B01"),
        "e2e4 d7d5 e4d5 d8d5" to OpeningInfo("Scandinavian: Main", "B01"),
        "e2e4 d7d6" to OpeningInfo("Pirc Defense", "B07"),
        "e2e4 g7g6" to OpeningInfo("Modern Defense", "B06"),
        "e2e4 g8f6" to OpeningInfo("Alekhine's Defense", "B02"),

        // 1. d4
        "d2d4" to OpeningInfo("Queen's Pawn Opening", "D00"),
        "d2d4 d7d5" to OpeningInfo("Closed Game", "D00"),
        "d2d4 d7d5 c2c4" to OpeningInfo("Queen's Gambit", "D06"),
        "d2d4 d7d5 c2c4 e7e6" to OpeningInfo("Queen's Gambit Declined", "D30"),
        "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6" to OpeningInfo("QGD: Traditional", "D35"),
        "d2d4 d7d5 c2c4 c7c6" to OpeningInfo("Slav Defense", "D10"),
        "d2d4 d7d5 c2c4 c7c6 g1f3 g8f6 b1c3 e7e6" to OpeningInfo("Semi-Slav Defense", "D43"),
        "d2d4 d7d5 c2c4 d5c4" to OpeningInfo("Queen's Gambit Accepted", "D20"),
        "d2d4 d7d5 c1f4" to OpeningInfo("London System", "D00"),
        "d2d4 d7d5 g1f3 g8f6 c1f4" to OpeningInfo("London System", "D02"),
        "d2d4 d7d5 g1f3 g8f6 e2e3" to OpeningInfo("Colle System", "D05"),

        // Indian Defenses
        "d2d4 g8f6" to OpeningInfo("Indian Defense", "A45"),
        "d2d4 g8f6 c2c4" to OpeningInfo("Indian Defense: Main", "E00"),
        "d2d4 g8f6 c2c4 e7e6" to OpeningInfo("East Indian Defense", "E00"),
        "d2d4 g8f6 c2c4 e7e6 g1f3 b7b6" to OpeningInfo("Queen's Indian Defense", "E12"),
        "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4" to OpeningInfo("Nimzo-Indian Defense", "E20"),
        "d2d4 g8f6 c2c4 e7e6 g2g3" to OpeningInfo("Catalan Opening", "E01"),
        "d2d4 g8f6 c2c4 g7g6" to OpeningInfo("King's Indian / Grünfeld", "E60"),
        "d2d4 g8f6 c2c4 g7g6 b1c3 d7d5" to OpeningInfo("Grünfeld Defense", "D80"),
        "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7" to OpeningInfo("King's Indian Defense", "E61"),
        "d2d4 g8f6 c2c4 c7c5" to OpeningInfo("Benoni Defense", "A56"),
        "d2d4 g8f6 c2c4 c7c5 d4d5 b7b5" to OpeningInfo("Benko Gambit", "A57"),
        "d2d4 f7f5" to OpeningInfo("Dutch Defense", "A80"),

        // Flank openings
        "c2c4" to OpeningInfo("English Opening", "A10"),
        "c2c4 e7e5" to OpeningInfo("English: King's English", "A20"),
        "c2c4 c7c5" to OpeningInfo("English: Symmetrical", "A30"),
        "g1f3" to OpeningInfo("Réti Opening", "A04"),
        "g1f3 d7d5 c2c4" to OpeningInfo("Réti: Main Line", "A09"),
        "f2f4" to OpeningInfo("Bird's Opening", "A02"),
        "b2b3" to OpeningInfo("Nimzo-Larsen Attack", "A01"),
    )

    private const val MAX_OPENING_PLIES = 16

    /**
     * Looks up the opening for the given list of UCI moves (e.g. ["e2e4", "e7e5"]).
     * Finds the deepest matching sequence. Returns null if not in opening book.
     */
    fun find(uciMoves: List<String>): OpeningInfo? {
        if (uciMoves.isEmpty()) return null
        val maxLen = minOf(uciMoves.size, MAX_OPENING_PLIES)
        for (len in maxLen downTo 1) {
            val key = uciMoves.take(len).joinToString(" ")
            openings[key]?.let { return it }
        }
        return null
    }
}
