package io.github.hazymorning.klangbild.domain

/** How this TV's speaker stands with Music Assistant. */
enum class SpeakerLink {
    /** Switched off, or not set up. */
    OFF,

    CONNECTING,

    /** Music Assistant knows the speaker and can play on it. */
    CONNECTED,

    /** Music Assistant's speaker port does not answer; tried again soon. */
    UNREACHABLE,

    /** Music Assistant is older than 2.10.7 and speaks an earlier version of the protocol. */
    TOO_OLD,

    /** Music Assistant speaks a newer version of the protocol than this app. */
    TOO_NEW,

    /** Music Assistant refused the speaker. */
    REFUSED,

    /** The speaker's own key cannot be opened at the moment (the keystore refused). */
    NO_KEY,
}
