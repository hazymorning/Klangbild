package io.github.hazymorning.klangbild

/**
 * Marks the tests that render pictures to be looked at and check nothing themselves: how the
 * screens look in every state, and motion as frames. They are left out of the everyday run and
 * render with `-Pklangbild.pictures`; CI renders them for every pull request and compares them
 * with the pictures of the branch it goes into, so a change in how anything looks is seen.
 */
interface Pictures
