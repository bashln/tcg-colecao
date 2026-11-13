package app.tcg.core.domain.model

enum class Currency { USD, EUR, BRL }

enum class PriceSource { TCGPLAYER, CARDMARKET, LIGAPOKEMON, JORANDA }

enum class CardVariant { NORMAL, HOLOFOIL, REVERSE_HOLOFOIL, FIRST_EDITION, PROMO }

enum class Condition { NEAR_MINT, LIGHTLY_PLAYED, MODERATELY_PLAYED, HEAVILY_PLAYED, DAMAGED }
