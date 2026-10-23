package io.github.hazymorning.klangbild.config

/** Seals the tokens Klangbild keeps (Home Assistant's and Music Assistant's) before they are stored. */
interface SecretBox {
    fun seal(plain: String): String

    /** The secret in [stored]: a sealed one opened, one stored before sealing as it is; null when it cannot be opened. */
    fun open(stored: String): String?

    /** Whether [stored] was written before secrets were sealed (it is sealed on the next write). */
    fun isPlain(stored: String): Boolean
}
