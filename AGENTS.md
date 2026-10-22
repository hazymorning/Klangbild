# Klangbild: notes for AI assistants

## What Klangbild is

Klangbild is a decoration for the room, a calm picture of the music that plays, and not a generic media
player. There are enough media players already. The design is the whole point of Klangbild and the
reason it exists.

Every change makes this one design better and keeps to its standard. Nothing turns it toward a
player, so there are no buttons or other controls on the screen (the remote works without anything
to point at) and nothing that outweighs the cover.
Something which is added meets the same standard as everything else.

## The project

Android TV "now playing" screen for Home Assistant and Music Assistant. Built for a 4K Android
TV device with a weak GPU in front of an OLED TV. Sideload only.
The UI speaks English (default) and German (`values-de`); code, comments, the README, the release
notes, CI and every other document are English. The README stays short (about 700 words, the longer
parts folded away in `<details>`) and is written like everything else (see the writing rules): what
Klangbild is and what it does, how the queue can show, the landscape in one line as the working
feature it is (its fold, "Landscape" under "More", holds its picture and what it shows), then a
short note (GitHub's `> [!NOTE]`, four sentences in the user's own easygoing voice, the first in
bold on a line of its own as the user asked, so no word hangs after it, and a no-break space keeping
its last two words together) on the two phases: the now playing screen,
practically finished, and a modern music visualizer, calm rather than psychedelic, that will one
day take the landscape's place (nothing promised about how or when); it ends by welcoming ideas in
the Discussions and linking the poll (discussion #51) where anyone who would like the landscape to stay can say so, a slim banner under it asking the same and leading there, the devices it runs on (Android TV 9 or later, and the slow drift that helps keep an OLED screen from burning in, which made the text upfront a wall; folded
away, streaming sticks with a system of their own, where the Android it is built on most likely
decides, said without more certainty than there is), setup with an optional third step that lets
Home Assistant open it (and, at the step that chooses the speaker, a note marked Important that a
speaker Music Assistant plays on mostly shows up twice and only Music Assistant's entity brings
its queue and lyrics, since people pick the wrong one), the remote (and, as a tip under its table, that its buttons can be changed), a friendly note on contributing (ideas in the Discussions, bugs in the issues), privacy and the license. It is a passion project and never sounds cocky: a line saying pull requests from others weren't accepted read that way and went. GitHub draws its alert boxes only outside folds (inside one, `> [!TIP]` shows as those very words), so a note in a fold is a plain quote with its word in bold on a line of its own (Important, Tip). It reads short and tidy: upfront only the
first picture and what it does (this TV playing over Sendspin in one sentence), with the day and
night picture under the one line that says what it shows (light by day, dark at night). Under
"More" each part is one line to click, in the order a newcomer needs them:
the devices it runs on, setup (its three steps folded inside it), the remote, the queue (its three
pictures inside its fold), Sendspin (only what the release notes claim), the landscape,
contributing and privacy (the badge's `#devices` anchor stands before the devices). Their titles
are about equally wide, since the user loves balance: Compatibility, Setting it up, TV remote, The
queue, Sendspin, Landscape, Contributing, Data privacy, and the setup's three steps alike, never a
personal "your" in them ("Your queue" read as odd). After the last step a `<br>` leaves the room
below it that the other folds have. Leave a
feature out rather than squeeze a sentence. Issues are for bugs: the issue form (`bug_report.yml`: fields to fill in, never a Markdown template, whose raw text and comments land in the box) says so kindly and sends ideas, feature
wishes and questions to the Discussions. The README's pictures are mockups in `docs/screenshots/`:
the app's own screens (`ReadmeShotsTest`, with the colors the lights behind the TV would show) set
in a room by `tools/make_readme_pictures.py`, made again when the look changes, and the download
button under the first paragraph, which downloads the newest APK at once
(`releases/latest/download/klangbild.apk`, so every release names its APK `klangbild.apk`), and
the poll's banner under the note (`poll.png`, `poll-light.png`: the landscape's mountains faint
behind a veil, at night from the README's landscape and by day from `LandscapeGalleryTest`'s summer
noon, the question on the left and a button on the right, the one picture with words of its own,
as the user asked; screens up to 640 px wide get `poll-small.png` and `poll-small-light.png`, the
button under the question, since on a phone the wide one's words shrank to a few pixels). No mockup carries text of its own. The first picture (the TV alone in the middle of its wall, the promo
film's song on it: "Klangbild" by hazymorning over the tagline, "Essential Mix, Vol. 1" up next)
and the button come in a light and a dark version, switched by the reader's theme (`<picture>` with
`prefers-color-scheme`): by day the light wall in daylight with the lights behind the TV off, as
they are by day; at night the dark wall with
the lights in the cover's colors. In both themes the promo film takes the first picture's place
(`docs/screenshots/film.avif`: 20 s without sound, its last half second fading into its first frame,
so it loops without a seam); only readers whose system asks for less motion see the stills
(`prefers-reduced-motion`). The film is an animated AVIF because GitHub plays one by itself and loops
it. A video uploaded to GitHub only starts when clicked, never loops and sits under a bar with its
file name, a `<video>` tag hasn't played for signed-in readers since October 2026, and a GIF bands
the light on the wall. The mockups stay calm and real, as a photograph of a room would
be: the lights' colors soft on a matte wall, no colorful backgrounds; the queue's three ways side
by side in its fold (the landscape's picture only in its own), one after another on the same evening, in one cozy room
worked out in 3D on a deep winter night (the queue off with a song on its cover colors, the queue
as a list on the dark wall, Cover Flow; the landscape with stars, a thin moon and the campfire), a
lamp's
light falling through its shade, candles, books and a record sleeve on a walnut sideboard; one song
by day and at night, each TV with as much wall above and below it as beside it.
The logo is `tools/logo.svg` (four rounded squares, each turned a little further toward the middle,
a ring and a light dot, blue on night blue), drawn from it by `tools/make_launcher_assets.py` for
the banner, the icons and the button, never scaled from a bitmap. Release notes are
easy to scan and in everyday words: a small heading for each kind of change there is (`### New`,
`### Better`, `### Bug fixes`), under it a few short points a user understands at once, nothing
technical. As short as the release allows; up to 300 words only when a release needs them and every
one is well spent. They never speak to the reader (no "you", no "your") and stay humble: no hype,
no exact claims ("more precisely", never "to a fraction of a second"), and only what is sure to
work; whatever the user is not sure of yet stays out, even when it was built (2026.10.5 left out
the screen switching to the TV's own music and "This TV" on the setup page).
`docs/` holds only the notes of the releases on GitHub and the README's
pictures: effort goes into the app, not into documents.

## Writing rules (texts in both languages, the setup page, comments, docs, commits, PRs)

- Write the way a developer explains their project to a friend who isn't very technical:
  natural, flowing prose in everyday English (US spelling), with sentences of different lengths.
  Connect ideas that belong together instead of giving each one its own short sentence, and say
  why something is the way it is when that helps. These notes are terse because they are a
  reference; nothing written for people should sound like them. On the TV, where room is tight,
  lines stay short, but they are still whole, natural sentences.
- A word limit is kept by leaving things out, never by squeezing what stays. Every sentence is
  one a person would say out loud: no clipped or telegraphic lines, no row of short sentences
  that each tick off a fact, no sentence tacked on to deliver one ("You switch between them in
  the quick settings."), no parts of the screen that act like people ("the last line names the
  next song"), no stiff or odd wording that only machine-written text has. The user hates that
  style: in the README it tells a first-time visitor that nobody cared, and the whole project
  loses its first impression. Read every sentence aloud before it goes in.
- The README never speaks in the first person (no I, me, my, we, our): the user finds it
  unprofessional there. It talks about Klangbild and to the reader. A text asked to be short says
  each point once in the fewest natural words, with no reasons, contrasts or examples nobody asked
  for; count the words before it goes in.
- No emoji, no em dashes, no dashes as punctuation, no exclamation marks, no bold for emphasis,
  no marketing words (seamless, effortless, elevate, delightful, immersive, robust, powerful,
  crafted), no filler, no poetic fragments. Commit subjects under 60 characters, PR descriptions
  under 300.
- No colons inside sentences in what people read (the README, release notes, pull requests,
  issues, discussions, replies there and new UI texts), since the user finds that style
  machine-written. Two sentences, or a comma, instead. Older menu explanations still have some.
- No AI branding anywhere, Claude's or any other tool's: no footers, no Co-Authored-By, no session
  links, no "Generated with" or "Generated by" lines in commits, PRs, comments, issues or releases
  (`.claude/settings.json` turns Claude Code's own attribution off). A footer a tool adds by itself
  is edited out right away. Commits carry the owner's name. A cloud session's own git identity is
  Claude's, so the session start hook (`.claude/hooks/session-start.sh`) sets the owner's there.
- No generated names in public either. A cloud session's own branch (`ccr-…`, `claude/…`) is never
  pushed; the work goes to a branch named after a German word from the world of sound instead,
  and this is standing permission to push there and open pull requests from it. Lowercase,
  umlauts as ae, oe and ue, ß as ss, one not on GitHub at the moment, and one that fits the work
  when one does, like `auftakt` for something new, `missklang` for a bug fix, `nachklang` for a
  follow-up or `stimmgabel` for tuning something. Others are `einklang`, `dreiklang`, `wohlklang`,
  `zwischenton`, `tonspur`, `tontechniker`, `bassgewitter`, `nachhall`, `obertoene`, `kammerton`,
  `mischpult`, `plattenteller`, `tonabnehmer`, `taktgefuehl`, `trommelwirbel`, `generalpause`,
  `klangfarbe` and `schlussakkord`. Any other name that is ours to choose follows the same idea.
- An English UI sentence is never longer than its German one (`LanguageTest` checks it).
- Never name a brand of light, speaker, media player or TV device in texts, names or comments.
- Whether a feature has been tried on a real device is internal: it stays in these notes and in
  what the assistant tells the user, never in the README, release notes, pull requests or discussions,
  where it reads as if nothing had been tested. Public texts simply claim nothing unverified.
- Music metadata keeps its typesetting (`TrackText`); dashes inside strings and test data are
  data. Never search and replace them blindly.
- Before a release, grep for em dashes, dashes as punctuation and emoji.

## Build and test

```bash
./gradlew :app:testDebugUnitTest                               # the checks (unit + Robolectric), not the picture tests
./gradlew :app:testDebugUnitTest -Pklangbild.pictures -Proborazzi.test.record=true   # everything, every picture to app/build/screenshots/
./gradlew :app:testDebugUnitTest --tests '*StageTest*' -Pklangbild.pictures -Proborazzi.test.record=true   # one class
python3 ci/compare-pictures.py before/ app/build/screenshots/ changes/   # what looks different (Pillow)
./gradlew :app:lintRelease                                     # Android Lint: every warning fails
./gradlew :app:assembleRelease                                 # R8-minified APK, signed with the debug key
python3 tools/make_launcher_assets.py                          # re-render banner + icons from the logo (Pillow)
python3 tools/make_readme_pictures.py                          # README mockups, download button, poll banner (Pillow, numpy; names like poll_banners make only those)
./gradlew :app:testDebugUnitTest --tests '*EndToEndTest*' -Pklangbild.sendspin=venv/bin/python   # the speaker against aiosendspin 10.2.0 (python3 -m venv venv && venv/bin/pip install 'aiosendspin[server]==10.2.0')
ffmpeg -i klangbild.mp4 -an -c:v libsvtav1 -preset 5 -crf 32 -g 300 -pix_fmt yuv420p10le -svtav1-params tune=0 -f avif docs/screenshots/film.avif   # the README's film from the promo film's MP4 (kept outside the repository)
```

- Local SDK: `local.properties` → `sdk.dir=/opt/android-sdk` (not committed). compileSdk 37,
  targetSdk 36, minSdk 28 (Android 9: the release signature, scheme v3, needs it).
- Kotlin warnings and lint warnings fail the build (`allWarningsAsErrors`, `warningsAsErrors`).
  A suppression always carries a one-line reason (`app/lint.xml`, `@Suppress` with a comment).
- Maven Central rate-limits shared IPs (HTTP 429): Gradle and Robolectric use Google's
  mirror first (`settings.gradle.kts`, `robolectric.dependency.repo.url` in `app/build.gradle.kts`).
- Picture tests render how the screens look and check nothing themselves; they carry
  `@Category(Pictures::class)` and only run with `-Pklangbild.pictures`, so the everyday run
  stays quick. A new test that only renders pictures gets the category too. Pictures are not
  committed: CI renders them all, uploads them, and on a pull request also renders the branch it
  goes into and compares every picture (`ci/compare-pictures.py`; the job summary lists what
  changed, the artifact `picture-changes` marks each changed pixel in red). A refactor must leave
  every picture identical; locally, compare a copy of `app/build/screenshots/` from before the
  change with the same script. Renders must be the same on every run: an animated sequence
  whose content is made off the main thread wraps it like a still (`LocalStill`).
- Tests that need words use `TestTexts.german` or `TestTexts.english()` (Robolectric). Renders
  default to German through `Fixtures.texts` (the catalog's music and rooms are German);
  `Fixtures.Still(t) { }` renders in other words.
- The progress bar refreshes in a loop of its own, so a stage never goes idle: stills wrap
  their content in `Fixtures.Still { }` (provides `LocalStill`), animated sequences use
  `createComposeRule()` with `mainClock.autoAdvance = false`. The static stages for stills
  (`Stage`, `FlowStage`) live in the test sources; the app only has its theaters.
- Motion is judged as contact sheets: `FlowMotionTest`/`TransitionTest` render frames to
  `app/build/screenshots/...`; tile them with Pillow and look at them with the Read tool.
- The emulator smoke test (CI) talks to a stand-in Home Assistant, `ci/fake_ha.py`, which
  speaks only the documented commands. With it running locally,
  `-Pklangbild.fakeHa=http://127.0.0.1:8123` lets `FirstLaunchTest` talk to it for a few
  minutes. The app logs its connection steps with the tag `Klangbild`, never a token.
- The speaker's end-to-end tests (`SendspinEndToEndTest`, `SpeakerEndToEndTest`) play from the
  server Music Assistant 2.10.7 runs, aiosendspin 10.2.0, started on this machine by
  `ci/sendspin_server.py`, and check every sample; they run only with
  `-Pklangbild.sendspin=<a Python with aiosendspin>`, as CI does. The smoke test plays a stream
  from it on the emulator, which has no sound.
- Run Gradle with a UTF-8 locale (`export LC_ALL=C.UTF-8`): test names and data hold umlauts.
- CI runs three jobs side by side: the release APK (the emulator smoke test and the key
  rotation checks start from it), all tests with their pictures (aiosendspin installed for the
  speaker's end-to-end tests), and lint. A release waits for
  all of them. A push or pull request that changes only the texts and
  pictures around the app (README, AGENTS.md, LICENSE, `docs/`, `tools/`, `.claude/`, the issue
  templates) runs nothing; tags and manual runs always run everything. The emulator jobs fetch
  their system image with `ci/install-system-image.sh`, which tries a broken download again.

## Signing and releases

- No key is in the repository. Release APKs are signed in CI (`ci/sign-release.sh`) with APK
  Signature Scheme v3 and a signing certificate lineage, so installs signed with the original
  key (every release up to 6.0.0) update in place on every supported API level, and the
  original key can no longer sign an update. The key, its password and the lineage exist only
  as repository secrets (`RELEASE_KEYSTORE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_LINEAGE`);
  they were made once, after 6.0.0, and cannot be made again: a new key would be refused by
  installed copies. `ci/rotation-check.sh` installs over an old-signed copy on API 28 to 36 in
  CI, with throwaway keys.
- Only a version makes a release: a pushed tag (`v2026.11.0`) publishes one at once; a manual CI
  run on main with the version as `release` leaves it a draft only the owner sees, so the notes
  can be read once more before it is published by hand on GitHub. Its text is
  `docs/releases/<tag>.md`. A beta (`v2026.11.0-beta.1`) comes from a manual run on any branch (a
  pull request's too), as a draft the same way. Publishing a draft creates its tag, whose own run
  publishes nothing (it finds the release out already). This session's git cannot push tags;
  manual runs go through the GitHub tools.
- The assistant never publishes a release on its own: no version tag, and a draft is published by the
  user, or on their word in so many words. Getting a release ready means its notes in
  `docs/releases/`, and when the user asks for it, a draft from a manual run and its link.
- Versions follow the calendar, the way Home Assistant's do, from the first release after 6.4.0
  on: the first release in a month is named after it (`v2026.11.0` for November 2026), and later
  releases that month count up the last number (`v2026.11.1`). A pre-release carries a hyphen
  (`v2026.11.0-beta.1`). The old numbers ended with 6.4.0. Android decides about updates by
  `versionCode`, which only ever grows, so the new names cost installed copies nothing.
- The "Tidy up" workflow (started by hand) keeps only the newest releases named by the calendar,
  ten by default, deletes the tags of the releases that go (and those left behind by a release
  deleted on the website), and deletes branches merged into main. The releases with the old
  numbers (6.3.0 and 6.4.0) and their tags stay for now. `docs/releases/` holds the notes of the
  releases kept.
- The app declares no signature permission of its own: the original key has no permission
  capability in the lineage, so an update that declares one is refused. The manifest removes
  the one AndroidX core adds for `ContextCompat.registerReceiver` (not used for that reason).

## Architecture (data source → state model → UI)

The packages build on each other in one direction, and `LayersTest` keeps it so: math, json,
color, text, art and sound below everything; domain (the app's model and rules) on them; data (Home
Assistant and Music Assistant) on domain, sendspin and sound; config (the settings, their store and menus) on data;
render, landscape and lights; sendspin (the protocol, on json alone) and speaker (this TV as a
speaker, on config, data, domain, json, sendspin and sound); app (the controller); ui on top. MainActivity,
KlangbildApp (the wiring) and SpeakerService stand in the root package and may use everything.

```
data/ha/        HaAuth (long-lived token, or a sign-in: refresh token + client id → 30 minute access
                tokens; a refused refresh is never tried again), HaRest (GET /api/states/<id>, all
                states and all actions (a script's fields) for the setup page), HaConnection (WebSocket: auth, subscribe_trigger with a
                state trigger for the followed entities, or subscribe_events state_changed for users
                who are not administrators; states read over REST when a subscription starts; a
                second swappable trigger for group members and lights via watch(); get_config;
                config/entity_registry/list_for_display (+ a sensor's display precision), read
                again on request; config/entity_registry/get_entries (unique ids, for every user);
                call_service (+ the call's context id);
                ping, backoff), EntityStore (state objects, never replaced by older ones, each
                state's context), PlayerParser (entity + MA get_queue; MediaKind from the MA URI,
                a book's description never an album; supported_features → PlayerAbilities),
                HaUrls, LocalNetwork (cleartext only to private addresses: the name before
                sending, the connected address on every hop), ArtworkUrls (CDN hi-res rewrites),
                HaSource (NowPlayingSource + LightBridge: player + sun.sun + the weather +
                "Everywhere" from the registry's MA players + a party of Music Assistant's Party
                plugin playing here (`party/player` every 60 s; `party/url`, the link its guests
                open), commands only where supported, seek in seconds, shuffle, the favorite
                button by its translation key, the entities of one's own followed (the buttons',
                their conditions', the values') with their precision and Home Assistant's
                temperature unit, a button run (a script with its variables and `player`), the
                lights' states and calls; this TV's own speaker shown instead of the one chosen
                while music plays on it (SpeakerSwitch), on the same connection, the commands,
                the queue and `player` going to the speaker shown), OwnSpeaker (this TV's own
                entity: its Sendspin id → Music Assistant's player around it (`players/all`) →
                the registry's unique id; asked again with a backoff, and from the start once the
                registry is there), QueueReader (the queue
                around the song that plays, retargeted when another speaker shows: MA
                queue (one read at a time, only for MA players with a queue) + the optional MA
                queue window; a Music Assistant song published once its queue has described it;
                the words of the song that plays, found by the URI Home Assistant names as what
                plays; with Music Assistant set up, its own queue and the songs to come asked
                every 15 s while a song plays, since Home Assistant hears nothing of a queue
                changed mid-song (and a song moved further down keeps the next one and the
                count): what comes next and the cards to come, published when they change; its
                word holds for that song only; the position from that same answer, to a fraction
                of a second, on Music Assistant's clock as its `time` command gives it, while it
                agrees with Home Assistant's), TrackMatch (one
                song in two descriptions), StationPictures (a station's song: the best picture on
                offer wins and holds)
data/ma/        MaClient (Music Assistant's own API, optional: POST /api with a long-lived token;
                `metadata/get_track_lyrics` on a client of its own with a long timeout;
                `player_queues/get`: the queue as it stands, its next item always up to date, and
                where it stands in it; `time`: its clock; `players/all`: the player around a
                Sendspin client),
                MaQueue (queue items around the current one → QueueWindow with the current item's
                speed, the current and next songs' URIs and the words they bring; books' authors,
                episodes' shows, chapters; pictures via imageproxy), MaLyrics (the words of the song
                that plays and the one up next: from the queue item, else asked, one song at a
                time, the one that plays first; kept by URI; a call for a song no longer playing
                or next canceled, its answer dropped; a version without the call: none),
                MilkdropSound (another speaker's sound from the MilkDrop plugin's relay, optional
                and off by default in Music Assistant: `/milkdrop_visualizer?player=<queue>` with
                the token as its first message, 1024-sample mono windows stamped on the server's own
                clock, through a TimeFilter of its own; the rate told by the windows' spacing;
                beats; close; only while read; none without the plugin)
data/           NowPlayingSource, LightBridge (what the lights need from Home Assistant), ArtworkLoader (size limits; the token only
                to Home Assistant's host, through a client without redirects; a larger rendition
                guessed from the address gets a short try and is not asked again for a while
                when it fails; two loads at a time; covers cached on disk), Backoff,
                Http (cancellable OkHttp fetch with a call timeout; HttpStatusException)
domain/         TrackInfo (+ pictureKey and sceneKey: a station's song by its picture too, kind,
                chapters), PlayerSnapshot (+ SpeakerGroup, QueueInfo, speed, PlayerAbilities),
                SpeakerLabel ("Living room + 5", "Everywhere", room names, "35%"), TrackText
                (typographic cleanup), Progress (runs at the item's speed) + SmoothedPosition,
                TimeText (track times, separator, "Speed 1.5×"),
                ScreenStateMachine, UpNext (the queue's list: a row per song, ids kept, how many
                follow and how long they play), CoverStrip (the cover flow's cards:
                one per picture, ids kept), Spoken (MediaKind, Chapter/Chapters/ChapterPlace:
                chapters, labels, jumps), SleepTimer (minutes or an end reached by playing),
                FieldClock (the color field's own time, shared by screen and lights), SunClock (the
                sun by the local clock, without `sun.sun`), LandscapePolicy (when the landscape takes
                the screen), EntityId (what an entity id looks like; THIS_TV, the setup page's
                stand-in for this TV's own speaker), SpeakerLink + SpeakerStatus (how this TV stands
                as a speaker), SpeakerSwitch (when its own speaker takes the screen and gives it
                back), ValueText (a value of one's
                own in words: precision, unit, on and off, a time of day), SourceState (what a source
                knows: LinkState, MaLink, EntityState), QuickAction (+ ActionKind) and Condition (a
                button of one's own and when it shows), OwnValue, Lyrics (Lrc: LRC read tolerantly, tidied or raw, plain words none;
                lines with breaks; LyricsCursor: the line in the middle a little early, a small step
                back held)
text/           Texts (the words of one language: resources, plurals, numbers, percent, dates, the
                clock in 12 or 24 hours), AppLanguage + PlatformLanguageStore (LocaleManager from
                API 33, the app's own preference below), Localizer (StateFlow<Texts>,
                refreshed on configuration, locale and time changes), LocalTexts/texts()
color/          Oklab, CoverPalette (k-means in Oklab → ScenePalette, inks, both walls), Lamp (the
                warm daylight every color is seen in, its hue, near-neutral colors), Appearance
                (LIGHT / DARK, by the sun), Contrast (WCAG luminance and contrast ratio)
math/           smoothstep, Rnd (a random source with a fixed seed), hash64 / hashUnit
json/           string(): a JSON string, or null for anything else
sound/          The sound feed for the visualizer to come: SoundFeed (what is heard at a moment on the
                monotonic clock, read only while someone reads), SoundFrame (loudness, four bands,
                onset, the latest beat and downbeat, SoundTiming: exact, close or none),
                SoundAnalyzer + Fft (1024-sample Hann windows: A-weighted loudness and bands on
                Sendspin's -60 dB to full scale, spectral flux against its recent mean; nothing
                allocated per window), SoundTimeline (frames and beats ahead of time, read as they
                come due), PcmSound (this TV's own playback, measured on the audio thread every 512
                samples while read, exact)
render/         StageGrid (the grid: the single cover's, or Cover Flow's with `flow`), ScreenSpec, RectDp, QrCode, PrintShape (the print's
                outline: rounded square or disc), CoverField (cover colors: ground + soft pools in
                the cover's colors, the veil with text shade) + FieldMotion (where each pool stands
                at a field time) + FieldModel (the field on the CPU, for the text shade), WallGlow
                (the artwork's light on the dark wall), LampLight (lamp tint for rest and menu),
                SceneBuilder (per-track precompute: Lanczos cover at exact px, wall, palette, flow
                card; relight), FlowGeometry (the shared perspective; the balance with cards
                missing), FlowCards (baked card textures), FlowLight (the pool of light above the
                cards), ImageOps (blur, dither, Lanczos; thumbnails keep transparency: what is clear
                gives no color)
art/            ChladniArt (missing-cover art), StableHash
landscape/      The landscape, a paper diorama: PaperMotif (folded mountains, forest lines, the
                clearing, the near banks, standees, birches, tent, boulders and the fire per label
                side, mirrored on the right), PaperBake* (sheets as two ink weights + the paper's
                light + coverage in boxes of their own, cut front to back with the shade of what
                stands in front, each on a Depth layer; snow caps, folds, silhouette forests,
                clustered crowns, flowers and fallen leaves, the tent; the meadow's cut-outs and
                the near banks' tall firs as Standee sprites about their feet; tissue clouds and
                flames, focus blur, shadows; its progress weighted by what each part takes, each
                part saying where it ends: BakeProgress), PaperGrain (pulp clouds, fibers, tooth per sheet),
                Inks/InkRaster/Coverage/Shapes (color matrices, exact AA, outlines), PaperLight
                (plates per hour: sky, key and fill light, folds, haze, fire, lantern; season
                papers, moonlight, the weather's skies and veils, mist, smoke, fireflies, label
                inks), LandscapeCamera (the journey: zoom and a wandering view within the paper's
                reach, CameraPose per Depth), SkyMap (south view, elevations from the skyline,
                stars by sidereal time), World (suncalc: sun, moon, phase; season by date),
                Weather + WeatherMood (state, cloud coverage and wind in its wind_speed_unit →
                cover, rain, snow, fog, storm, wind) + WeatherGlide (the weather shown, gliding
                toward the weather outside over minutes; kept in `LandscapeUi`, so the picture and
                the lights read the same), CloudField (lanes, hashed, never repeats),
                Weatherfall (snow and rain at three depths) + MistField, LandscapeClock (real time; a
                day in two minutes only for measuring), Noise
lights/         LightSync (any light behind the TV via Home Assistant: a color per light, or a
                white, a brightness or just on, as the light can; saved with scene.create
                snapshot_entities and given back with scene.turn_on, our own note as a fallback;
                rate limits, takeover by others), LightColors (CoverLightColors: the cover's colors
                kept apart, placed as in the cover, moving on with motion; LandscapeLightColors: the
                landscape's own palette: sky, meadow or moonlit wood, mountains, sun glow, fire),
                LightColor (xy of a color's hue at its purest, grays as white (neutral by day,
                warmer at night), a color temperature for white-only lights, brightness floor)
app/            NowPlayingController (single source of truth → StateFlow<UiState>, with the texts;
                settings from the menus, the setup page and the launch's extras taken at once,
                the newest written one at a time) and its parts: MenuControl (the groups, then a group's settings), QuickControl (+ the
                own buttons, the party code),
                Seeking (jumps with the remote), SleepControl, LightTargets, HeldStage (the stage
                kept from before the clock and when it goes; the clock or the landscape held while
                a song's first scene is made), QueueSwitch (the queue's way chosen and not on screen
                yet: `UiState.queueComing`); ControllerHost
                (what the parts need), Scenes (scene cache + builds off the main thread; another
                frame lets go of the builds for the one left), Thumbs
                (the list's small covers at their exact size, made ahead, one at a time after the
                scenes; a cover that took too long stands in as its figure and is asked again),
                SoundSelection (the one sound feed: this TV's own playback while the music shown
                plays here, the MilkDrop feed for Music Assistant's other speakers, none otherwise)
                (KlangbildApp/AppGraph in the root package: http, tokenHttp without redirects, localizer,
                SpeakerHub; SpeakerService beside them)
config/         Settings (+ QueueDisplay (Off / List / Cover Flow), DisplayOptions, VolumeDisplay, Intensity, AppearanceMode, BackgroundMode,
                MotionLevel, RoomLights, RoomLightBrightness, tvSpeaker and speakerOn), SettingsStore (DataStore, written whole
                in one step; tokens sealed by SecretBox: AES-GCM with a key in the Android keystore;
                `migrate` at the start brings an earlier version's store up to date: its tokens
                sealed, its view (SINGLE or FLOW) moved to the queue's own key, touching nothing
                else, since rewriting all would open the tokens and lose one the keystore cannot
                open at that moment), SettingsMenu (groups,
                entries, values, explanations, steps; what needs something set up first dimmed),
                QuickMenu (the quick settings' entries and values: Queue, Landscape On / Off, Lyrics
                On / Off (a quiet On for a song without words), Party code On / Off, Settings),
                QuickActions (the own buttons as stored: a name, an entity, a script's variables, the
                condition: a state or attribute is, is not, is above or below a value; a list counts
                its entries), OwnValues (the values in the status line as stored),
                LaunchExtras (am start extras and klangbild:// links: English names and values, those
                of earlier versions still taken (`window_view` for the landscape, `view` for the
                queue), the German ones; `tv_speaker` an extra, never a link)
sendspin/       Sendspin, Music Assistant's protocol for synchronized audio (the spec's main, as
                aiosendspin 10.2.0 in Music Assistant 2.10.7 speaks it): Noise (KKpsk2 over
                BouncyCastle's lightweight X25519, ChaCha20-Poly1305, SHA-256 and HMAC; no provider
                registered), Keys (client id, Sentinel and pairing PSKs, the SP:0 pairing token),
                Frames (transport messages, fragments), Messages (the JSON envelope, hello, state,
                commands), TimeFilter (the spec's two-dimensional Kalman filter, as the reference
                has it), Session (client/init to the stream: handshake, re-handshake, pairing,
                bursts of 8 time messages, available only once synced, leave, goodbye; an older
                or newer Music Assistant told apart: only a server that closes before it answers
                counts as older, a connection lost on the way is lost)
speaker/        SpeakerConnection (ws://<Music Assistant host>:8927/sendspin through the client
                without redirects; backoff, a try at once on wake), SpeakerStore (the key and the
                pairings sealed, in a store of their own; an identity that cannot be opened is
                never replaced; output delay, volume, mute), ChunkQueue (timed PCM, filled by the
                connection, emptied by the audio thread, nothing allocated), Playback (10 ms blocks
                at the moment the server meant: the output's timestamps trusted once they move on,
                put in place at once on a start, a clear, a gap or an underrun, a frame dropped or
                repeated against drift; volume ramps, output delay, silence while another app has
                the sound), AudioSink + AudioTrackSink (48 kHz 16-bit stereo, timestamps, routing),
                ArrivalDelays (the min_buffer_ms asked for), MonoClock (the monotonic clock in µs
                as a plain long: nothing boxed on the audio path, where "no time" is a sentinel,
                never null), Speaker (all of it, an audio thread at urgent priority while a stream
                plays and 30 s after; a key the keystore refuses asked for again, less and less
                often; playing only while its group plays; volume, mute and output delay kept one
                write at a time; its playback and Music Assistant's beats (visualizer@v1, beats
                only) as a PcmSound)
setup/          MiniHttp, SetupHandler (one-time code, the sign-in through Home Assistant's login
                page: state, callback, code exchange, revocation of what is not kept; API, tokens
                bound to their address, the own buttons and values, every entity and the scripts'
                fields; this TV's own speaker left out of the list, where "This TV" stands for it;
                Music Assistant's speakers first, and while there are any a line saying to choose
                one of those, since a speaker mostly shows up twice;
                the pairing code only through the API), SetupPage (HTML in the language shown;
                entities chosen by searching),
                SetupServer (+ discovery; Host, Origin and content type checked, a few
                connections at a time, slow ones closed, only while the setup screen shows and
                the TV has an address of its own, tried again until it has; a request that has
                arrived is still answered when it stops, checked against the address it came to;
                once a stop has returned, the port takes no connection: it waits for the thread
                blocked in accept(), which holds the socket open until it lets go)
ui/             KlangbildRoot (screen switch, OLED drift, menu overlay, peek, the quick settings'
                hint; provides LocalTexts),
                stage/ (Stage layers, Typesetting, StageTheater transitions, ProgressBar (a range:
                the chapter; another song, a bar of its own), StageModel + StageTexts, FieldLayer:
                the pools as gradient quads, drawn small and scaled; Lyrics: the words in the
                column, set once per song off the main thread, one canvas, LyricsViews keeping
                their places through a change;
                PartyCode: the party's code where the cover stands, one picture per wall;
                PlayedLines: a song's own time, next and chapter once it no longer plays),
                Motion (+ StageTick: one frame tick for bar, field and words; rememberFrames: that
                tick, or a loop of its own for a part shown alone; animateGlide: a list's glide that
                carries on from its pace),
                OffMain (bitmaps off the main thread, at once in stills),
                flow/ (FlowStrip: cards as quads, a card's textures mixed exactly, a sign in the
                middle card's place, FlowLook: the strip's look before a new one; FlowTheater:
                keyed strip transitions),
                theme/Type (Jost + DM Sans, `titleType()`/`textType()`, Figures, ink shift),
                MenuScreen (+ MenuGrid; two levels), QuickBar (+ QuickGrid; a long row glides),
                Shimmer (a value whose choice is on its way: a band of its ink passing over it),
                RestScreen, SetupScreen, Changes (`playChange`, `RisingText`: one change at a time, hurrying,
                held lines), DebugOverlay (frame timing, and the sound feed's meter: its timing in
                words, bars for loudness, bands and onset, a dot on each beat; read and drawn only
                while it shows), landscape/ (Landscape: bake ahead of time on a thread of low
                priority, the picture on screen on a thread of its own, never the shared ones; 20 fps tick, season cross-fade, the weather as its `WeatherGlide` has it;
                LandscapeWaiting: words over a bar while the first bake goes, WaitingPace keeping the
                bar's pace even; PaperView: sky, far
                range, clearing and near banks as layers redrawn only when the light moves on,
                each only as tall as its paper, moved by the camera as a whole; stars, clouds,
                mist, snow and rain, swaying cut-outs and firs, the fire, its smoke, sparks and
                light, fireflies every frame; LandscapeScreen + LandscapeLabel: the mini player on the
                near bank, standing still while the camera moves)
dream/          KlangbildDream (DreamService with ComposeView)
test only       SyntheticCovers + Catalog (generated test covers and tracks, test://cover/NAME, a
                book and an episode, invented words, the README's aurora), Fixtures, TestTexts, the static stages for stills
```

## Design principles (do not regress)

They hold for every change, optional ones included. A setting that brings back what a principle
rules out breaks it just as much as a new default would, so a second layout, a mode with buttons or
a look to choose from stays out even when it is off by default.

UI words are given in English with the German in parentheses where it matters.

- **Object, not app, but a well-made one.** No buttons, no icons, no chips, no harsh white.
  The artwork is the heaviest element and always the tallest: the text block beside it takes
  at most 62% of its height (`BLOCK_SHARE`, measured from the first cap height to the last
  baseline, never from text boxes).
- **Centered, measured on what is seen** (the user checks this; `CenteringTest` measures rendered
  pixels in both languages): the stage grid (960 × 540 dp, `StageGrid`) is artwork 368 dp at
  88 dp from the left, exactly centered vertically; 64 dp gap; text column 352 dp; 88 dp right
  margin, as much room left of the artwork as right of the progress bar. Text block centered on
  the artwork; the column stands within the artwork's straight edge, a corner radius in from its
  top and bottom (the artwork stays the tallest on what is seen; its edges recede at the round
  corners and where a dark cover meets the dark wall; until 6.1 the descenders of "Up next" hung
  below it and the column read as taller): the status line ("9:47 PM · Living room + 5") hangs
  there with its cap height, the descenders of "Up next" (Als Nächstes) end there
  (`Fonts.DESCENT`); every line starts on the column's left edge. The rest clock's figures (top to baseline,
  ink-centered across from the outlines' exact bounds: side bearings differ) stand in the very
  middle, its lines under it; setup: code + longest text line centered as a group; menu on the
  stage grid. No optical lifts, no re-hanging. The whole screen drifts only ±6 dp against
  burn-in. Square artwork has 8 dp corners.
- **Two faces, one voice:** Jost (Owen Earl, OFL) for titles ≥ 24 sp, the large clock,
  headings and all figures (`titleType()`); DM Sans (Colophon, OFL, optical size = size in
  sp) for everything ≤ 21 sp (`textType()`). Both shipped unmodified, both cap height 0.70.
  Sentence case only: no capitals, no letter-spaced labels, no italics, no third family
  (those read as "AI template"); the platform's own system face is out (it reads as the TV
  device's own UI). Title 44/40 sp (2 lines, weight 400, −1%), then 34/30 sp (3 lines, 420,
  −0.5%), 26 sp (3 lines, 430), 24 sp (4 lines); Japanese/Chinese/Korean titles at 0.88×. A
  version ("Radio Edit") is a quiet subtitle line (DM Sans). Artist 21 sp/500; album, work
  17 sp; labels, times, status, next 15 sp/440, nothing smaller. Large lines are pulled left by
  their extra side bearing (`opticalIndent`). Times are set in Jost with `Figures`: digits in
  equal cells (DM Sans has no tabular figures and a narrow 1), colon raised 0.123 em; the
  rest clock keeps natural figures, set by hand as outlines (`ClockFigures`): its colon raised
  to the figures' middle as measured at its weight (on the baseline it sagged 18 dp below them
  until 2026.10), each 1 drawn in by most of its wide side bearings (it stood apart), and it
  dissolves into each new minute. Neither face has
  U+202F, U+2009 or U+2002: `Texts` turns the platform's narrow spaces into U+00A0.
  Titles ≤ 3 lines, prefer breaks after section dashes, no mid-word breaks
  (`breaksInsideWord`), a word of one to three letters stays with the next ("Spiegel / im
  Spiegel"); artists on one line (smaller, then "and others" (u. a.)); the year leaves the
  album line before anything is cut; "Up next" drops the artist before it cuts; edge emoji
  dropped (`Emoji.trimEdges`), inner ones dimmed; one separator `TimeText.SEPARATOR`;
  `TrackText.typeset` for dashes and no-break spaces after abbreviations; RTL lines isolated;
  in UI prose a short word opening a sentence never ends a line, and articles and adjectives
  stay with their noun (no-break spaces in the string resources).
- **Text color:** three levels only: title; artist, clock and the bar's fill; everything
  else (like white at 100 / 80 / 62%; on paper Oklab L 0.24 / 0.37 / 0.49, the quietest
  still 4.5:1), near-neutral with a breath of the cover's hue (chroma ≤ 0.009), never #FFF,
  never ivory. Every level keeps 4.5:1 (`DesignRulesTest`; on paper `lightestInkOn` holds it).
- **No night mode:** the stage looks the same at every hour, seen in one warm daylight
  (`Lamp`); only the wall (appearance) and the lights' brightness follow the sun. A night mode
  that hid lines at dusk looked like a crash (v3.0.2) and was removed in 5.2; its dimmed, warmer
  light went in 6.1. Never hide anything by the time of day.
- **Light and dark wall (`Appearance`):** automatic by the sun (light from sunrise to sunset,
  elevation −0.833°, 0.5° hysteresis; the local clock without `sun.sun`) or fixed. The light
  wall is warm paper (Oklab L 0.915) with a breath of the cover's color pulled toward warm and
  kept within hues 50° to 90° (a cool cover gives less color, never mint), never white.
  **Nothing frames a cover**: no shadow, no hairline, no mat, on either wall and in Cover
  Flow; every soft shadow under the artwork read as an uneven, broken frame (v5.1;
  `NoFrameTest` checks rendered pixels). A wall change cross-fades the stage in 2.4 s, its
  lines and the status line with it (until 2026.10 the new inks stood at once, a pale ghost on
  the old paper; `WallChangeTest`); every ground reaches beyond the screen's edges
  (`BEYOND_EDGES_DP`), so no strip of the old wall ever shows at a side (`EdgesTest`).
- **Light (`WallGlow`):** a tight halo in the colors of the artwork's own edges, the same on
  every side: each wall point takes the nearest edge color, averaged over a stretch that
  grows with distance (diffuse, no streaks); at most L* ≈ 17 at the edge and never more than
  0.4 × the edge's own luminance (a dark cover gives little light), falling as a Gaussian
  with σ = 26 dp, gone before the text column (which stands on black). It fills 2 dp under
  the artwork's edge so the anti-aliased rim never shows black. The 36 dp next to the screen
  edges stay black (`EdgeBandTest` checks rendered pixels), faded in as a product of a
  horizontal and a vertical ramp (a min() of edge distances creases along the diagonals).
  One bitmap per scene, drawn as one rectangle, dimmed through a color matrix.
- **Progress:** a classic bar: 4 dp, round ends, no knob, track 16%, fill at the artist's
  level, elapsed left, the track's length right (only one number ticks). Refreshed about 30
  times a second aligned to frames, on its own layer; jumps > 1.5 s glide in 350 ms; paused,
  the fill dims. Hidden by setting (beside the song's words always), back on a key press. A Music
  Assistant player's position comes from Music Assistant's queue once it has been asked (every
  15 s while a song plays), to a fraction of a second where Home Assistant has whole seconds, as
  long as the two agree within 1.5 s; a jump Home Assistant has heard of wins until the queue
  tells it too.
- **Per frame only cheap work:** blur, palette, resampling and glow happen once per track in
  `SceneBuilder`; animations only change alpha, transform and color filters, read in draw
  lambdas.
- **Every cover fills its square:** a small one (a station's picture) is enlarged once with
  Lanczos. No passe-partout: a smaller print in a mat (v5.0) read as a half-transparent frame
  on paper and, optically centered, as a print that had slipped (`CoverFillTest`).
- **Calm motion** (`Motion`): a track change (1 s) never goes dark: the new artwork comes up
  over the old (0 to 600 ms), the old one goes once it is covered (600 to 750 ms), the light
  turns to the new colors (0 to 800 ms) while the old wall stays whole until the new one is
  up; old text leaves in 220 ms, new lines rise one after another from 260 ms (the dip through
  dark of 5.1 read as a glitch on every skip). Up to 4 s without music between two songs, the
  stage stays (`Timing.betweenSongsMs`) instead of flashing the rest screen. Same picture
  (album), or the song's words coming or going: only text; same track, new scene (a better
  cover): quiet cross-fade. Pause (600 ms) dims light, artwork and text; nothing moves, so the
  lines hung on the artwork's edges stay aligned.
  Mute is only a word; a lost link dims like a pause. Nothing slides, nothing bounces.
  Screens switch by fading through dark; a switch to or from Cover Flow leaves what shows until
  the first scene of the other is made, then the two cross at once (until 2026.10 the screen
  waited in the dark, which read as stuck). A song that comes after the clock, the setup page or the
  landscape shows its own stage once its scene is made, never the stage left from the song
  before, nor the same song's from a wall that has changed meanwhile or from before a switch
  to or from Cover Flow. The clock or
  the landscape stays until then (the clock's words a moment longer), never the dark, also
  when no stage was kept; the keys, the quick settings and the lights act on what shows, and
  over the landscape the song keys act on what plays, as always. A screen that says something
  is wrong, and the setup page, go through the dark, since what they said no longer holds
  (until 2026.10 the song before came up first and the lights took its colors; `HeldStageTest`).
  **One change at a time:** what arrives during a change waits until it has landed, then the
  newest goes next (`snapshotFlow … conflate()`).
  Only another *song* hurries the running change (`playChange`, ≤ 350 ms); a card joining the
  strip, a queue update or a better cover just wait. Lines on their way up stop where they
  are when a change hurries and leave from there (`RisingText`): the skipped song's lines
  never come up; if the skip comes to nothing (back to the same song), they rise the rest of
  the way. A change that begins between composing a frame and drawing it is drawn as the stage
  stood until it is composed (until 2026.10 the lines blinked out for that frame;
  `SongChangeBlinkTest`). A stage still on screen after its song has ended (the next song's
  scene still being made, or its lines leaving) keeps its own time, its own "Up next" and its
  chapter, its time read for that song by name, since the player reports the next song a
  moment before the screen shows it (until 2026.10 a stage left waiting showed the new song's
  time, "Up next" and chapter, and lines leaving showed its "Up next" and lost their own time
  and chapter; `OwnLinesTest`).
  The landscape's label keeps its own cover and time the same way while it leaves.
- **The queue as a list** (Queue: List; `QueueText`, `UpNext`): beside the cover, which never
  moves; the text column set for it, as for the song's words, which win while they show. The song hangs under the status line: its title from 34 sp down, two lines at most;
  artist (17 sp, the artist's ink and weight), album and year on one line (the year leaves
  first, then the album); then the bar as everywhere. Then "Up next" with how many follow and how
  long they play ("11 tracks · 48 min", the length only when every item is known), then the rows.
  Every row alike and as tall as its small cover (30 dp, corners 2.5 dp): title (17 sp, the
  artist's ink) over its artists (15 sp, quiet), its length on the title's baseline; only whole
  rows, every one in the same inks (a quieter last row was a fourth ink below 4.5:1). Spacing,
  baseline to cap height: 18 dp from each quiet line to what it leads (the status line to the
  title, "Up next" to the first row), 12 from the title to its artist, 20 to the bar, 30 from the
  bar's times to "Up next" (half again the room above the bar, so the bar belongs to the song),
  16 between rows; a mixed rhythm (rows of one and two lines, numbers beside covers, a row cut by
  a gradient) read as clutter (`CenteringTest`). A title too long for two lines at 24 sp ends
  after a whole word. The bar coming for a moment (or going): "Up next" and the rows make room
  in 400 ms first, the bar fades in once the room is mostly open (and goes before it closes),
  a row that no longer fits goes before it would pass the column's foot. No hint in the place
  of "Up next" while the list shows. Music only, with Music Assistant's queue and something to
  come; otherwise the cover as with the queue off. Off and List switch as the column's text changes,
  the cover and the lights stay; a new song moves the list up (rows that stay glide to their
  places, the first goes as the new title comes, a new row comes at the foot). The player names
  a song the list announced a moment before its queue is read: meanwhile the list before moves
  up by one, so the song changes in one change. The rows are the queue, not the song: they come
  the whole way also when another song hurries a change. A queue changed beneath the song moves
  only the rows (those that go first, rows put in once their place is clear); rows that changed
  order go and come again rather than pass through each other (`QueueGlideTest`,
  `QueueMotionTest`). The small covers are asked for after the song's own picture and made one at
  a time, so they never hold it up or the song: a row whose small cover is still on its way shows
  without it, and it comes in where it stands (`Thumbs`); on another wall they stay whole under
  the new ones, as the cover does. A stage that leaves keeps the bar's room as it stood
  (`QueueRooms`).
- **Cover Flow** (Queue: Cover Flow, `StageGrid.flow`): middle cover 300 dp centered 303 dp from the left and
  vertically, two cards per side turned 60° in one shared perspective (outer edges 222.5/255 dp
  beside the center, 92% height), mirrored on a glossy floor; column from 598 dp, 314 dp wide,
  48 dp left of the outermost card and right of the column; every other rule holds. A card is
  one textured quad (print, corners and, on the dark gloss only, its reflection baked; a
  half-size copy against shimmer, since HWUI has no mipmaps). While a card turns between its two
  copies, or the strip takes a new look (a better cover, a song of the same card whose cover
  differs, the other wall: one strip, each card going over from its texture before), the card
  is the mix of its textures laid over the wall as one. A card's own two copies on the dark wall:
  what lies beneath is darkened once by the card, then each copy adds its share (DST_OUT, then
  ADD; no layer); on paper (no mirror image, an opaque print) the full copy goes beneath just
  strong enough that the half one laid over leaves the card at its alpha. Textures of two
  different cards (a new look: another cover, which may have clear parts, or the other wall) are
  mixed in a small layer of the card's own, now and then for a moment; each texture is lit as on
  the wall it was made for, and in a new look of the same song cards that come, go or change
  places fade where they stand. So the half-transparent mirror image never counts twice (until 2026.10 the copies were laid over one another, the mirror image grew
  denser as a card turned and fell back in one frame, and a new look came up over the old strip
  with the same jump as it landed; `FlowMirrorTest`). Side cards at 55% / 40%, and on
  the dark wall a much lighter card steps back further beside a dark middle one, coming forward
  with the turn, never before the strip moves (`FlowStepBackTest`; until 2026.10 it flashed
  bright on the turn's first frame). Dark: a soft
  oval of light above the middle card in its top edge's color, ≤ L* 8 and following that
  edge's lightness (`FlowLight`); paper: no reflection, no shadow. The strip turns over in
  1.3 s (`Motion.Turn`, peak ≤ 2× average speed); leaving cards swing aside early so crossing
  cards never intersect (tested); cards that make way dissolve first (300 ms), then new ones
  roll in opaque from beyond the strip's end, their center crossing the screen at an even
  pace (`FlowGeometry.rolled`), never two covers on top of each other. Cards are pictures,
  not songs (`CoverStrip`, `TrackInfo.pictureKey`); ids are kept so every change animates
  card by card.
- **Cover Flow with cards missing** (the first song, the last, no queue): what is there stands
  with the text in the middle of the screen, as much room left of the outermost card as right
  of the column, measured on what is seen (`FlowGeometry.balance`); the column keeps its 40 dp
  to the cards to come, or the single cover's 64 dp to the middle card when none come; the full
  strip stays exactly on the grid. A new balance comes with the strip's turn, and the column
  never passes over a card: it makes room first, or the cards leaving on the right go first.
  Lines that leave go from where they stood, new ones rise at the new place. The dark wall's
  light fades out before the column wherever it stands (`FlowLight.shade`, laid over as it is
  drawn), the text shade on cover colors reaches every place the column can stand, and the
  edge fade never reaches the middle card (`EdgeBandTest`, `NoFrameTest`).
- **States say it in words**, in the status line, place first and state last: "Living room
  · Paused", "Kitchen · Muted", "Volume 35%" (3.5 s), "Everywhere"; "Reconnecting…" in the
  progress bar's place.
- **Values of one's own** (`OwnValues`, the setup page's section 8, at most 3; setting
  "Values"): the state of any Home Assistant entity, or one of its attributes, after a name of
  one's own, between the time and the place ("21:47 · Outside 12.3 °C · Living room + 5").
  Numbers in the language's figures with Home Assistant's display precision and their unit
  (a weather entity's own units, a thermostat's in Home Assistant's), on and off in words, a
  time as a time of day (`ValueText`); what Home Assistant cannot tell shows nothing, so a
  template helper can hide a value. The place always has its room: values that do not fit
  beside it are left out from the last, never cut. They tick like the clock, no cross-fade.
- **The song's words** (`Lyrics`, `LyricsText`; Lyrics (Songtext) in the quick settings and
  under Information, remembered like the queue's setting; Music Assistant only, nothing else to
  set up): the text column set for them, not a screen. The line sung now stands centered on the artwork (first cap height to last
  baseline, `CenteringTest`) in the title's ink; the lines around it in the quiet ink, each
  fading as a whole with the distance of its nearer edge (whole within 40 dp, like the settings
  list), gone before its far edge comes within 8 dp of the status line, the song or the bar.
  One size per song, no size or weight changes: Jost 30, 26 or 24 sp, the largest at which no
  line takes more than two rows (three at 24; two voices count apart), balanced breaks, a short
  word kept with the next, Japanese, Chinese and Korean at 0.88×. The list glides one step in
  500 ms, starting 300 ms before the line is sung, the light going with it (a glide that comes
  while one runs carries on from its pace); a break (an empty line, a lone note or
  "[Instrumental]", at least 4 s) only dims the line; before the first line it waits unlit in
  the middle; a jump lets the words go, then come at the new place; a step back of less than
  1.5 s (a corrected position) never moves them. The picture never moves. The status line keeps
  its place, title and artist stand where "Up next" stands, title block, album and bar give way
  (a key brings the bar for a moment, above the song, the words making room). Switching them on
  or off, and words that come late, use the stage's text change (`Change.LYRICS`); not in the
  landscape. The words come from the queue item (`media_item.metadata`: `lrc_lyrics`, else
  LRC in `lyrics`), else from `metadata/get_track_lyrics` (Music Assistant 2.8 and later; raw
  LRC before 2.10) with a 75 s timeout of its own (Music Assistant asks its online source at
  most once in 30 s), never holding up the queue reads; the song up next is asked ahead. Songs
  only, synced only: plain words are none. Set once per song off the main thread; a frame only
  moves one canvas.
- **Remote:** OK plays and pauses, held it opens the settings; left and right change the song
  (spoken word: jump); down opens the quick settings and, pressed again, closes them; up brings
  the landscape and, pressed again, leaves it (both also show hidden parts for a moment; with
  "Up and down: Volume" both turn the speaker's volume instead); back closes what lies on top (a
  settings group goes back to the groups first, then the settings, the quick settings, the
  landscape), else the app. Commands go to Home Assistant only where the player's
  `supported_features` allow them.
- **Quick settings** (`QuickMenu`, `QuickBar`; down on the remote): one row along the bottom
  edge, rising 22 dp as it fades in, where the key points: Queue (Off / List / Cover Flow, taken
  0.9 s after the last press so none in between shows, List only with Music Assistant connected;
  chosen over the landscape, it leaves it; while the way chosen is on its way, from the press
  until the stage shows it, its value waits in the name's ink and a soft band of its own ink, 96
  dp (64 lit only two letters and read as too faint), passes over it in 1.2 s, then rests 0.4 s; a pass that has begun always ends; the menu's
  chosen Queue line shimmers too, waiting in the ink of a name at rest; over the clock, the setup page or trouble nothing is
  made, so nothing shimmers (`QueueSwitch`, `Shimmer`)), Landscape On / Off (at once), Lyrics On
  / Off (only with Music Assistant connected and a song on stage; while this song has no synced
  words it still says On, in the name's ink and chosen no brighter than another value at rest,
  since a word of its own, "None" (Keiner), read as switched off until 2026.10; OK turns them
  off), Party code On / Off (only while a party plays here), Favorite (with Music Assistant; a press
  that comes back with an error says Failed and logs Home Assistant's answer, and once Music
  Assistant counts the song as a favorite it says Added),
  Lights On / Off (off remembers the level), Shuffle On / Off (only where the player can
  shuffle; the sleep timer lives in the menu), then the own buttons, and last Settings: Open,
  the way into all settings. Left and right choose, OK changes, down, up or back close, 20 s
  alone close them. Each entry is its name (15 sp, quiet) over its value (20 sp), on the stage
  grid from 88 dp; its place is as wide as its widest value (only the last is as wide as what it
  says), so nothing moves when a value changes; the chosen one lit from below like the menu's
  line. A row longer than the room (in German the built-in entries alone are) glides to show the
  chosen entry whole, entries beyond its ends faded out; every entry fits the room on its own
  (`LanguageTest`). An opaque veil (black, paper) from 448 dp down, softened above: nothing
  behind shows through. They leave the landscape as it was, but never bring it over a screen
  that says something is wrong or the setup page. Until they have been opened once
  (`Settings.quickSeen`), the stage tells how: 3 s after it comes, for 12 s, its bottom line
  says "Arrow down opens the quick settings" (Pfeil runter öffnet die Schnelleinstellungen) in
  the place and style of "Up next" (`QuickHintTest`), coming and going in its cross-fade; not
  with "Up and down: Volume", not beside the song's words, not in the screensaver (any key
  ends it).
- **Own buttons** (`QuickActions`, the setup page's section 7, at most 6): a name of one's own
  (at most 32 characters; without one, the entity's own words) and a Home Assistant entity,
  whose kind says what OK does: a script runs (`script.turn_on` with its variables (the fields
  it declares, asked for on the setup page, and any others as `name: value` lines) and the
  speaker followed as the variable `player` unless it names one, so one script can ungroup
  whatever plays), an automation runs its actions (`automation.trigger`), a scene is
  activated, a button is pressed, a switch, input boolean or light is toggled and shows On /
  Off. The value is the verb ("Run"), then "Done" or "Failed" for 4 s; a call still running
  after 4 s (a long automation) counts as taken. An entity Home Assistant does not know or has
  as unavailable is left out of the row, and so is a button whose condition does not hold
  (`Condition`: an entity's state or attribute is, is not, is above or below a value, as Home
  Assistant's own state conditions; a list counts its entries, so `group_members` above 1 means
  the speaker plays in a group; anything more is a template helper's state) unless it is just
  saying how its call went. Buttons are told apart by their place: one script can be two
  buttons with other variables.
- **Party code** (`PartyCode`; Music Assistant's Party plugin with guest access on): while a
  party plays to this speaker (`party/player` names its queue, asked every 60 s; two failed
  questions in a row end it; a server without the plugin is not asked again), the quick settings
  offer Party code Off / On, off at first. On: the QR code of the link guests open (`party/url`,
  asked again every 60 s, since a code expires) stands where the cover stands, a card like the
  setup's on whole pixels with the cover's corners and light, dimmed in a pause; in Cover Flow
  in the middle card's place, with its mirror image, the cards turning beneath it (those passing
  the middle fade under it). The song's lines stay beside it, the wall's light and the lights
  stay the song's. It comes and goes in a quiet cross-fade once its picture is made, stays while
  songs change, and takes the new paper with the wall. Turning it on leaves the landscape;
  while it shows, the landscape comes only when chosen and a pause never turns into the clock,
  so the guests can always scan it. Off, or when the party ends or plays elsewhere: the cover
  comes back, and the next party starts without it. `PartyCodeTest` reads the code from rendered
  pixels beside a single cover and in Cover Flow, on both walls, on the cover's colors and in a
  pause.
- **Settings menu** (OK long-press, Menu, or Settings in the quick settings), in two levels as
  on every TV: first the groups, then the settings of the one opened. It opens on the groups; OK
  or right opens a group on its first line, Back goes to the groups (on the group left) and from
  there out. The stage's grid, heading (Settings, or the group's name) and the chosen line's
  explanation on the left (on the groups, what is in each), lines on the right; the chosen line
  sits at the screen's middle in a soft, low band of light (σ 120 × 12 dp, ≈ L* 10) that lights
  it alone; the list glides through it and fades with distance. Into a group or back out, the
  level left fades (140 ms), then the new one comes (220 ms), already resting on its line: two
  lists never lie over each other. A held key runs the list on evenly (`animateGlide`: a glide
  that comes while one runs carries on from its pace; until 6.3 it started over from a
  standstill at every repeat and stuttered), a step moves only the cursor, and the explanation
  goes while the key is held and comes back with the line it is let go on; the quick settings'
  row glides the same way. Values are words, changes apply at once (left and right clamp, OK
  wraps; right held on a group opens it once, and changes nothing in it until pressed again),
  closes itself after 60 s. Every setting is always there: one that cannot work until
  something is set up on the phone (Lyrics and Play here without Music Assistant: "Needs Music Assistant"; the
  lights and the values while none are chosen: "Set up") is dimmed, never brighter when chosen
  than another line at rest, its explanation says what it needs, and OK opens the setup. Options
  show or hide parts, choose how the queue shows, the wall and the language, and dim the light; no
  fonts, colors or layout variants. Labels are the common words people know from TVs and phones,
  values plain words: groups Picture (Queue: Off / List / Cover Flow, Appearance, Background: Plain /
  Cover colors, Intensity, Motion: Off / Slow / Medium / Fast), Information (Clock, Progress
  bar, Next track, Album and year, Speaker group, Volume, Values, Lyrics), Lights (Brightness:
  Off / 10 to 100% with a slider, Lights that are off: Turn on / Leave off), Landscape (When
  paused, While playing, Mini player: Bottom left / Bottom right / Off), Remote (Control music,
  Up and down: Landscape, menu / Volume, Audiobooks, podcasts: Jump / Change track), Timer
  (Sleep timer, Turn off after, When turning off: Close app / Screen saver), System (Home
  Assistant, Music Assistant, Play here: On / Off, Language: System language / English / Deutsch). Label + value fit
  the 352 dp column on one line in both languages (`LanguageTest` measures every value).
- **Language:** English is the default, German in `values-de`. The menu's Language line uses
  Android's per-app language (`LocaleManager` from API 33, where the choice also shows in the
  system's app settings; the app's own preference below API 33) and applies at once
  everywhere, the phone's setup page included, without restarting the activity. Every text
  comes from `Texts` (resources, plurals, numbers, percent, dates); the time of day follows
  the system's 12 or 24 hour setting. A recreated activity never applies its launch extras
  again.
- **Cover colors** (setting "Background", `BackgroundMode.COVER`): the cover's own colors as the
  wall, edge to edge and 18 dp beyond (`OVERSCAN_DP`: the ±6 dp drift never shows a border; the
  only wall without the black edge band, `EdgeBandTest` checks the edges are filled). A deep
  ground plus one mass per cover color (up to 5), placed where the color sits in the cover and
  pushed ≥ 0.42 screen widths apart; lit cores in the 3 most characteristic masses; 1 to 2
  soft pools of light in front, on the picture's side only (never under the text). Far =
  large and soft, near = smaller, lighter, a little firmer (depth of field). Dark tones L 0.15
  to 0.56 (rich, chroma ≤ about 0.5 × L), paper L 0.86 to 0.975 (pale washes). Each pool is one
  quad with a radial gradient in a quarter-size layer (`FieldLayer`), saturation 1.2 after
  mixing; grain against banding; a veil with a shade under the text worked out per cover
  (sampled over 20 min of motion, aiming at 4.8:1 so the quietest text keeps 4.5:1, tested on
  rendered pixels). No shadow under the artwork, no vignette. `FieldModel` computes the same
  field on the CPU (text shade, lights). On color the lower inks stand a little lighter
  (0.82 / 0.71); the progress bar takes the cover's leading hue.
  "Intensity" sets how strong the colors are. Cover Flow on the field: the cards pass the
  screen's edges (`FlowStrip(edges = false)`).
- **Motion** (Off / Slow / Medium / Fast = speed 0 / 0.3 / 0.6 / 1.0; the main layer turns
  once in about 470 / 230 / 140 s): only while music plays, on the field's own clock
  (`FieldClock`): a pause freezes it, nothing jumps; screen and lights read the same clock. One
  frame tick (about 30 a second) for the field and the progress bar, never two loops out of step,
  and none while nothing reads it (no bar shown, the colors still, the settings over the stage).
  In the landscape the label's bar is redrawn in the scenery's frames.
- **Lights** (any light Home Assistant controls, chosen on the setup page; Off or 10 to 100% in
  steps of 10, default 60%, a slider in the menu): brighter by day, the level as set while the
  sun is up, down to × 0.45 at night (sun elevation 6° to −8°), so they show in daylight and
  stay calm in the evening. Three lights, three colors (`LightColors`). From the cover
  (`CoverLightColors`): its most telling colors (color counts more than size), kept ≥ 0.09
  Oklab apart, placed as they sit in the cover (leftmost color left, rightmost right, the
  third above the TV); one color gives itself and its neighbors ±32° round the hue circle, a
  gray cover three whites. With motion the colors move on from light to light (a step per 90 s
  of field time, resting 70% of it). In the landscape (`LandscapeLightColors`) its own palette:
  above the TV its sky (the weather's too, as the picture shows it), on the side away from the fire the clearing's green
  (by night the moonlit wood), on the fire's side the mountains' paper and at night the fire's
  light, the glow of dawn or dusk on the sun's side; worked out every 30 s of its sky's time,
  sent at most every 20 s in 15 s fades, no pause dimming while it shows. Each light shows
  what it can (`supported_color_modes`): a color goes as the xy of its hue at its purest, 42
  to 92% of the way from white (light on a wall looks far paler than a screen), and Home
  Assistant fits it to the light; only real grays are white (4300 K by day, 3000 K at night,
  never the amber of 2700 K, which read as plain orange in 5.1); a white-only light gets a
  color temperature of the color's warmth within its own range (Home Assistant's own
  conversion would turn red into cool white); a dimmer gets a brightness; an on/off light is
  only switched on. Dark colors softly; pause × 0.5 (after 2 s). `transition` only for lights
  that support it. Before taking a light over its state is saved with `scene.create`
  (`snapshot_entities`, one scene per light, our own note as a fallback); it is given back
  with `scene.turn_on` after the music has ended for 5 minutes, when the option goes off and
  when the app closes (lights that were off: dimmed, then their scene without a fade), and
  the scenes are deleted. At most one call per light a second, none for invisible changes, a new
  song after 1.5 s (quick skips cost nothing) in a 2.5 s fade, drift every 10 s in 8 s fades. A
  light someone else switches off, sets to white or changes a lot, during the music or after it,
  is theirs until the next session; what a person or an automation does counts at once, even
  while a fade settles. The lights' states arrive a moment after they are followed: taking over
  waits for them (≤ 10 s) and a light that appears later joins in; a light taken over stays
  followed until it is given back, so the dim and the fades reach it (until 2026.10 the lights
  were let go as the music ended, and went back without a fade); lights no longer chosen, or
  switched off, go back at once, over the clock too; a failed call is retried once, for that
  light only. "Lights that are off" (Lichter, die aus sind): Turn on (the default, as above) or
  Leave off: then a light that is off is never switched on and not saved; one switched on during
  the music is saved and joins in, one switched off is let go and joins in again when switched
  on, and after the music each is as it was before it first joined. Leave off chosen while they
  glow: the lights that were off go back off, the others keep their colors; one that Home
  Assistant still reports on (a slow light reports late) is not taken up again for 10 s.
- **Skip flicker** (`FlipGuard`): a return to an item left within 5 s is held 2 s and shown
  only if it is still what plays; new items pass at once, and so does the remote's "previous".
- **A song arrives whole** (`QueueReader.awaitsDetails`): Home Assistant reports a Music Assistant
  song first with a 512 px picture and without its year; the queue tells the rest about 0.4 s
  later. A song the queue announced as next is described at once; any other waits for its
  queue read (at most 2 s), while the song before it stays as it was, all of it. So the first
  picture is the song's own at full size, and nothing changes a moment after a song starts.
- **A station's pictures** (`StationPictures`): Music Assistant shows the station's picture
  first and the song's once it has looked it up, and may fall back for a while. The song's own
  picture (named by the queue window, or one with an address of its own) ranks above any
  other, the station's (named by the queue, or seen with another song) lowest; while a song
  plays its picture never steps down and never returns to one it has left. Home Assistant's
  proxy renews its token every few minutes: a picture's identity leaves the token out
  (`pictureId`), and a station's song gets a new scene when its picture changes
  (`TrackInfo.sceneKey`).
- **Audiobooks and podcasts** are told by Music Assistant's URI (`…://audiobook/…`,
  `…://podcast_episode/…`): a book's description (where an album would be) never shows, an
  episode's show stands where an artist would. With Music Assistant: the chapter in the album's
  place (a number-only name as "Chapter 7", a long one cut after a whole word), the bar spans
  the chapter, "Up next" the next chapter, the speed in words ("Speed 1.25×") and the position
  running at that speed. Left and right jump back 15 / ahead 30 s (presses add up, the bar
  shows it at once), held they go by chapters; setting "Audiobooks, podcasts": Jump / Change
  track. A held key never repeats a command.
- **Sleep timer** (menu, no setting): Off / 15 / 30 / 45 minutes / one hour / End of chapter /
  End of episode (track, audiobook); the status line says "Pauses at 11:10 PM". An end counts
  only when playing reaches it (a jump across moves the timer on); the item's end is paused
  1.5 s early so the queue never moves on first. It lives in the app: only while Klangbild runs.
- **Home Assistant** is used only through its documented interfaces (WebSocket API, auth API,
  REST, media player, light, scene and weather entities). Sign-in from the phone through Home
  Assistant's own login page (authorization code; client id and redirect URI on the TV's setup
  server); a long-lived token still works. A refresh token Home Assistant refuses is never
  tried again (each try counts as a failed login there). Tokens are sealed at rest, only ever
  go to the address they were made for, never follow a redirect and are never logged; a new
  address without its credentials drops the old ones; a replaced sign-in is revoked.
- **This TV as a speaker** (`sendspin/`, `speaker/`, `SpeakerService`; System: Play here (Hier
  abspielen), off by default, dimmed with "Needs Music Assistant" without it; the setup page: a
  checkbox in Music Assistant's section, the pairing code folded beneath it while the speaker
  runs): the TV becomes one of Music Assistant's speakers over Sendspin, connecting to the
  address it already has on port 8927 (8095's /sendspin is the web player's), PCM 48 kHz 16-bit
  stereo only, encrypted (Noise KKpsk2) and offering unpaired access, so Music Assistant 2.10.7
  takes it without a pairing step; an older one is told apart and the menu's explanation says
  what it needs. It is named as the TV is (its device name), never like a web or app player
  (Music Assistant hides those). Its key is made once and kept: a new one would be a new speaker
  in Music Assistant and a new entity in Home Assistant. A foreground service of the media
  playback kind runs it while the setting is on, from the moment Klangbild is opened (Android
  starts none by itself after a reboot), with or without the screen; with the setting off
  nothing of it runs, no service, thread or connection (`SpeakerServiceTest`), and every
  picture stays as it was. `available` only once the clock filter has settled (3 bursts, error
  under 5 ms); each 10 ms block placed by the output's timestamps (none from before the start,
  stalls and frames never written refused), at once on a start, a clear, a gap or an underrun,
  else a frame at a time outside 100 µs (±100 ppm held within 1 ms, `PlaybackTest`); the volume
  is a gain ((v/100)^1.5, 20 ms ramp), since CEC pins the system volume (at 100% the status line
  leaves it out, since the TV's own volume, which the app cannot see, sets what is heard); the output delay (0 to
  5000 ms) is kept. The audio thread runs at urgent audio priority and allocates nothing per
  chunk. Another app taking the TV's sound for a moment silences it in time underneath, for good
  makes it leave its group; the TV waking or an HDMI device coming or going puts it in place
  anew. Music that starts on it shows instead of the chosen speaker, on the same connection
  (`OwnSpeaker` finds its entity for every user): paused it stays unless the chosen speaker
  plays, stopped it gives the screen back after 4 s (`SpeakerSwitch`); the keys, the volume, the
  queue and a button's `player` act on what shows, and another speaker's volume is no change to
  show. "This TV" at the top of the setup page's list follows it alone (`EntityId.THIS_TV`),
  and its own entity is left out of that list; while it does, Play here stays on (the menu's OK
  opens the setup, where another speaker is chosen first, and a launch extra cannot switch it
  off), and its explanation still says what keeps the TV from playing. Tested end to end against aiosendspin 10.2.0, and in daily use on the user's own TV, where it plays well.
- **The sound feed** (`sound/`, `SoundSelection`; for the visualizer of the second phase, shown
  nowhere yet but in the debug overlay's meter): for each short stretch, loudness, four bands and
  onset, the latest beat Music Assistant knows, when it is heard on the monotonic clock, and how
  exactly that is known. This TV's own playback is measured from the samples on their way to the
  output (exact); another Music Assistant speaker's from the MilkDrop plugin's relay (close: as
  close as Music Assistant knows that player's position; none without the plugin); anything
  else has none. One analysis for both, cheap enough for the box; nothing is measured, asked or
  connected while nobody reads it, and the meter draws frames only while the overlay shows.
- **Music Assistant** is optional: without it Klangbild works from Home Assistant alone; with
  it (address + long-lived token on the setup page, checked before storing) the cover flow
  shows the whole queue and the album line its year.
- **Generated art:** Chladni plates are ring modes (two or three nodal circles inside 0.64 of
  the radius) made wavy by a second mode (3 to 6 lobes, rounded: amplitude ≤ 1.5 r/(n² − 1),
  share capped so circles never touch), mirror-symmetric, no sand beyond 0.74 to 0.84 of the
  radius. No nodal diameters (crosses, wheels, rosettes), no single circle or two lobes ("O").
- **Rest screen:** large light clock ("9:47" or "21:47" as the system is set, 132 sp, natural
  figures) in the very middle of a dim pool of light (the user wants it centered; burn-in care
  is the ±6 dp drift, the changing figures and the screen ending after "Turn off after"); one
  plain sentence when something is wrong ("Home Assistant can't be reached.").
- **Landscape** (Landschaft, `landscape/`; the window view until 6.3): a full, working feature of
  the first phase, standing in for the second phase's modern music visualizer (at home in a
  living room, not psychedelic), which takes its place once the right approach is found, unless
  people ask to keep it in the poll (discussion #51, linked from the README); never
  call it useless or unfinished, just give it little room (the README: one line and a fold), and
  no new work goes into it. A diorama of real paper, not
  a flat illustration (the smooth paper cut read as a comic) and not a photograph. Every sheet
  with its grain (pulp clouds, fibers, tooth; breaking off at every cut), edges cut by an uneven
  hand, mountains folded from card (the faces toward the sun or moon lit, some light from the
  upper right always), snow caps of torn white paper a hair wider than their mountain, forest
  strips as silhouettes, standees (firs, birches) with a narrow margin of the card's light core
  (a breath of the tree in it, never plain white) and slim trunks (a tall fir's no bell), broad
  trees with clustered crowns, soft shadows and the shade of what stands close in front, the far
  sheets a little out of focus. Mountains and forest, a campfire of crumpled tissue in the
  clearing (always burning; at night the light of the clearing; sparks rising, thin smoke
  drifting off with the wind), a tent beside it (a lantern flickering in its door at night),
  boulders, flowers in spring and summer, fallen leaves in autumn, fireflies on summer nights;
  no lake, no figures, animals or events, no lightning flashes. Plates per hour (morning and
  evening apart) blended by the sun; papers per season; white paper sinks into the night more
  than color; moonlight; seasons by date (south half a year on), shapes re-cut in 1/16 steps and
  cross-faded (blossom only colors). **The weather is seen** (`Weather` from the entity's state,
  `cloud_coverage` and `wind_speed` in its `wind_speed_unit`, glided in over minutes): a clear
  day's deeper blue; strips of tissue, heaps as it clouds over, an overcast ceiling of heaps
  hung upside down; the slate of rain, a storm's dark ceiling over a pale horizon; the bright
  gray of snow; fog's milk swallowing the far range; rain and storm darken the land, falling
  weather veils the far sheets. Snowflakes of white paper (each with a breath of shadow) and
  rain as fine threads fall at three depths with their layers and land on the meadow; mist lies
  as thin tissue in the valley and the forest (fog, rain, calm mornings), never over the
  clearing. The wind drives the clouds (on a clock of their own, so a gust never makes them
  jump), bends the flames and the rain, and sways the cut-outs. The user found 5.1 far too
  subtle: motion must be **visible**, calm but plain. Clouds cross the screen in a few minutes;
  the cut-outs sway 1.1° to 4° about their feet (leaning into the wind, gusts shared by the
  meadow), the tall firs on the near banks (sprites cut whole beyond the screen) a little less;
  never a loop, 20 fps; light recomputed every 10 s, each step below one level. The camera
  (`LandscapeCamera`) journeys: zoom 1.01 to 1.11 about the screen's middle, the view wandering as
  far as the paper reaches at that zoom (±6 dp drift on top), but never so far that the near
  bank would leave the label (`BANK_MARGIN`); a swing in about two minutes, sums of sines in the
  golden ratio; each layer follows by its `Depth` (sky 0.15, far range 0.35, clearing 0.65, near
  banks 1), so the parallax is plain to see. The label stands still: it is no part of the
  scenery (riding the bank's scale and pan, its text shimmered in 5.2); the camera keeps its
  bank under it. No edge and no gap between layers ever shows (`LandscapeCameraTest` renders every
  corner of the range, `LandscapeCameraJourneyTest` checks six hours of poses, the bank under the
  label included); stills show it at rest. No preview in the menu (removed in 5.2: never build
  features nobody asked for); the time-lapse is only the `landscape=timelapse` extra for
  measuring. Comes after the pause delay (default 3 min; the rest clock first), during music
  only if set, never over trouble, setup or the menu, nor by itself while a party's code shows;
  up on the remote or the quick settings (Landscape) bring it whatever plays, until it is left
  (up again, back, Landscape Off, or a choice of the queue there). Every key starts the delays again; one that came by
  itself goes with any key, which still does its work. Baked ahead only while it shows or is on
  its way (`LandscapePolicy.onItsWay`); the bake is let go 10 s after it stopped coming and when the
  app goes to the background (tens of megabytes at 4K). While a first bake keeps one waiting
  (after 1 s), one quiet line, "Preparing the landscape…" (Landschaft wird erstellt …, DM Sans
  17 sp in the rest screen's quiet ink), stands 16 dp over a bar drawn like the stage's progress
  bar (240 dp), the two together in the screen's very middle (cap height to the bar's lower
  edge). The bar fills as the paper is cut, at an even pace: each part of the bake says where it
  ends, and between the reports the bar runs on at the pace measured so far, never past that end
  and never back (`WaitingPace`); once the picture is ready it runs to its end and the sign goes
  while the landscape comes up (`LandscapeWaiting`; a turning ring read as too plain in 6.1, the
  far range's skyline traced as a line was not liked in 2026.10). The label (cover 56 dp,
  Jost 24 / DM Sans 17, bar) lies on the dark near bank (snow only along its edge), the fire on
  the other side; beside the cover one block exactly centered on it: the title's cap height to
  the bar's lower edge (filling the cover's height), or without a bar (a station) to the last
  baseline (`LandscapeLabelTest`). Its inks come from the bank and keep 4.5:1 on its lightest grain
  (`PaperLightTest`). Every sheet in its own box, baked only down to where nearer paper hides
  it, each piece cut in a box of its own; sky, far range, clearing and near banks stand as
  layers between light changes, each only as tall as its paper, moved by the camera as a whole;
  what falls is drawn in a few batched calls (fill and memory: see the CI's measuring run; 5.1
  measured about 2.9 screens of fill per frame and 55 MB at 1080p).
- **The phone's setup page** follows the same rules: one (system) sans face, sentence case, no
  italics, no gradients, no shadows, no pill buttons, no icons, light or dark as the phone is
  set, in the language the TV shows. An entity is chosen by searching its name, never from an
  endless list. The setup server runs only while the setup screen shows (saving ends it, and
  the answer to the save still reaches the phone), answers only requests for its own address,
  takes API calls only as JSON from its own page and with the six-digit code (a wrong one is
  answered only after a second), and closes slow connections.
- Never claim device behavior that was not verified on the device (frame rate, CEC wake,
  dream on the TV device): mark it as unverified.
