package io.github.hazymorning.klangbild.art

/** Deterministic 32-bit hash of a string (FNV-1a), for seeding generated art. */
fun stableHash(text: String): Int {
    var h = 0x811C9DC5.toInt()
    for (ch in text) {
        h = h xor ch.code
        h *= 0x01000193
    }
    return h
}
