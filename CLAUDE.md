# Klangbild: notes for Claude

Android TV "now playing" screen for Home Assistant and Music Assistant. Built for a 4K Android
TV device with a weak GPU in front of an OLED TV. Sideload only.
The UI speaks English (default) and German (`values-de`); code, comments, the README, the release
notes, CI and every other document are English. The README stays at about 500 words, in plain,
complete sentences that a reader understands at once: what Klangbild is and what it works with, its
views, leaving it on, setup, the remote and privacy. Leave a feature out rather than squeeze a
sentence. Its pictures are mockups in `docs/screenshots/`: the app's own screens
(`ReadmeShotsTest`, with the colors the lights behind the TV would show) set in a room by
`tools/make_readme_pictures.py`, made again when the look changes, and the download button under
the first paragraph. The mockups stay calm and real, as a photograph of a room would be: the
lights' colors soft on a matte wall, no colorful backgrounds; the three views, one after another
on the same evening, in one cozy room worked out in 3D on a deep winter night (Single cover on its
cover colors, Cover Flow, the landscape with stars, a thin moon and the campfire), a lamp's light
falling through its shade, candles, books and a record sleeve on a walnut sideboard; one song by
day and at night, the only picture of the light wall.
The logo is `tools/logo.svg` (four rounded squares, each turned a little further toward the middle,
a ring and a light dot, blue on night blue), drawn from it by `tools/make_launcher_assets.py` for
the banner, the icons, the mockups and the button, never scaled from a bitmap. Release notes are
easy to scan and in everyday words: a small heading for each kind of change there is (`### New`,
`### Better`, `### Bug fixes`), under it a few short points a user understands at once, nothing
technical. As short as the release allows; up to 300 words only when a release needs them and every
one is well spent. `docs/` holds only the notes of the releases on GitHub and the README's
pictures: effort goes into the app, not into documents.

## Writing rules (texts in both languages, the setup page, comments, docs, commits, PRs)

- Plain everyday English, US spelling, short sentences. No emoji, no em dashes, no dashes as
  punctuation, no exclamation marks, no bold for emphasis, no marketing words (seamless,
  effortless, elevate, delightful, immersive, robust, powerful, crafted), no filler, no poetic
  fragments. Commit subjects under 60 characters, PR descriptions under 300.
- No attribution lines in commits, PRs or comments: no Co-Authored-By, no session links, no
  "Generated with".
- An English UI sentence is never longer than its German one (`LanguageTest` checks it).
- Never name a brand of light, speaker, media player or TV device in texts, names or comments.
- Music metadata keeps its typesetting (`TrackText`); dashes inside strings and test data are
  data. Never search and replace them blindly.
- Before a release, grep for em dashes, dashes as punctuation and emoji.

## Build and test

```bash
./gradlew :app:testDebugUnitTest                               # all JVM tests (unit + Robolectric screenshots)
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true  # same, writes PNGs to app/build/screenshots/
./gradlew :app:testDebugUnitTest --tests '*HaloStageTest*' -Proborazzi.test.record=true   # one class
./gradlew :app:lintRelease                                     # Android Lint: every warning fails
./gradlew :app:assembleRelease                                 # R8-minified APK, signed with the debug key
python3 tools/make_launcher_assets.py                          # re-render banner + icons from the logo (Pillow)
python3 tools/make_readme_pictures.py                          # README mockups + download button (Pillow, numpy)
```

- Local SDK: `local.properties` → `sdk.dir=/opt/android-sdk` (not committed). compileSdk 37,
  targetSdk 36, minSdk 31.
- Kotlin warnings and lint warnings fail the build (`allWarningsAsErrors`, `warningsAsErrors`).
  A suppression always carries a one-line reason (`app/lint.xml`, `@Suppress` with a comment).
- Maven Central rate-limits shared IPs (HTTP 429): Gradle and Robolectric use Google's
  mirror first (`settings.gradle.kts`, `robolectric.dependency.repo.url` in `app/build.gradle.kts`).
- Screenshots are not committed as baselines; tests render to `app/build/screenshots/`
  (CI uploads them as an artifact). A refactor must leave every render identical: compare
  the PNGs of a run before and after with Pillow.
- Tests that need words use `TestTexts.german` or `TestTexts.english()` (Robolectric). Renders
  default to German through `Fixtures.texts` (the catalogue's music and rooms are German);
  `Fixtures.Still(t) { }` renders in other words.
- The progress bar refreshes in a loop of its own, so a stage never goes idle: stills wrap
  their content in `Fixtures.Still { }` (provides `LocalStill`), animated sequences use
  `createComposeRule()` with `mainClock.autoAdvance = false`. The static stages for stills
  (`Stage`, `FlowStage`) live in the test sources; the app only has its theatres.
- Motion is judged as contact sheets: `FlowMotionTest`/`TransitionTest` render frames to
  `app/build/screenshots/...`; tile them with Pillow and look at them with the Read tool.
- The emulator smoke test (CI) talks to a stand-in Home Assistant, `ci/fake_ha.py`, which
  speaks only the documented commands. With it running locally,
  `-Pklangbild.fakeHa=http://127.0.0.1:8123` lets `FirstLaunchTest` talk to it for a few
  minutes. The app logs its connection steps with the tag `Klangbild`, never a token.
- Run Gradle with a UTF-8 locale (`export LC_ALL=C.UTF-8`): test names and data hold umlauts.
- CI runs three jobs side by side: the release APK (the emulator smoke test and the key
  rotation checks start from it), the JVM tests (they upload the screenshots) and lint. A
  release waits for all of them.

## Signing and releases

- No key is in the repository. Release APKs are signed in CI (`ci/sign-release.sh`) with APK
  Signature Scheme v3 and a signing certificate lineage, so installs signed with the original
  key (every release up to 6.0.0) update in place on every supported API level, and the
  original key can no longer sign an update. The key, its password and the lineage exist only
  as repository secrets (`RELEASE_KEYSTORE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_LINEAGE`);
  they were made once, after 6.0.0, and cannot be made again: a new key would be refused by
  installed copies. `ci/rotation-check.sh` installs over an old-signed copy on API 31 to 36 in
  CI, with throwaway keys.
- Only a version publishes a release: a pushed tag (`v6.0.0`), or a manual CI run on main with
  the version as `release` (the release creates the tag). Its text is `docs/releases/<tag>.md`.
- The "Tidy up" workflow (started by hand) keeps only the newest releases, two by default, and
  deletes branches merged into main. `docs/releases/` holds the notes of the releases kept.
- The app declares no signature permission of its own: the original key has no permission
  capability in the lineage, so an update that declares one is refused. The manifest removes
  the one AndroidX core adds for `ContextCompat.registerReceiver` (not used for that reason).

## Architecture (data source → state model → UI)

```
data/ha/        HaAuth (long-lived token, or a sign-in: refresh token + client id → 30 minute access
                tokens; a refused refresh is never tried again), HaRest (GET /api/states/<id>, all
                states and all actions (a script's fields) for the setup page), HaConnection (WebSocket: auth, subscribe_trigger with a
                state trigger for the followed entities, or subscribe_events state_changed for users
                who are not administrators; states read over REST when a subscription starts; a
                second swappable trigger for group members and lights via watch(); get_config;
                config/entity_registry/list_for_display (+ a sensor's display precision);
                call_service (+ the call's context id);
                ping, backoff), EntityStore (state objects, never replaced by older ones, each
                state's context), PlayerParser (entity + MA get_queue; MediaKind from the MA URI,
                a book's description never an album; supported_features → PlayerAbilities),
                HaUrls, LocalNetwork (cleartext only to private addresses: the name before
                sending, the connected address on every hop), ArtworkUrls (CDN hi-res rewrites),
                HaSource (NowPlayingSource + LightBridge: player + sun.sun + MA queue (one read
                at a time, only for MA players with a queue) + "Everywhere" from the registry's
                MA players + the optional MA queue window + a party of Music Assistant's Party
                plugin playing here (`party/player` every 60 s; `party/url`, the link its guests
                open), commands only where supported, seek in seconds, shuffle, the favorite
                button by its translation key, the entities of
                one's own followed (the buttons', their conditions', the values') with their
                precision and Home Assistant's temperature unit, a button run (a script with its
                variables and `player`), the lights' states and calls; a Music Assistant song
                published once its queue has described it; the words of the song that plays,
                found by the URI Home Assistant names as what plays; with Music Assistant set up,
                its own queue asked every 15 s while a song plays, since Home Assistant hears nothing
                of a queue changed mid-song: what comes next and the cards to come),
                StationPictures (a station's song: the best picture on offer wins and holds)
data/ma/        MaClient (Music Assistant's own API, optional: POST /api with a long-lived token;
                `metadata/get_track_lyrics` on a client of its own with a long timeout;
                `player_queues/get`: the queue as it stands, its next item always up to date),
                MaQueue (queue items around the current one → QueueWindow with the current item's
                speed, the current and next songs' URIs and the words they bring; books' authors,
                episodes' shows, chapters; pictures via imageproxy), MaLyrics (the words of the song
                that plays and the one up next: from the queue item, else asked, one song at a
                time, the one that plays first; kept by URI; a call for a song no longer playing
                or next canceled, its answer dropped; a version without the call: none)
data/           NowPlayingSource/SourceState/LinkState, ArtworkLoader (size limits; the token only
                to Home Assistant's host, through a client without redirects; a larger rendition
                guessed from the address gets a short try and is not asked again for a while
                when it fails; two loads at a time; covers cached on disk), Backoff,
                Http (cancellable OkHttp fetch with a call timeout)
domain/         TrackInfo (+ pictureKey and sceneKey: a station's song by its picture too, kind,
                chapters), PlayerSnapshot (+ SpeakerGroup, QueueInfo, speed, PlayerAbilities),
                SpeakerLabel ("Living room + 5", "Everywhere", room names, "35%"), TrackText
                (typographic cleanup), Progress (runs at the item's speed) + SmoothedPosition,
                TimeLine (track times, separator, "Speed 1.5×"),
                ScreenStateMachine, StageView (SINGLE / FLOW), CoverStrip (the cover flow's cards:
                one per picture, ids kept), Spoken (MediaKind, Chapter/Chapters/ChapterPlace:
                chapters, labels, jumps), SleepTimer (minutes or an end reached by playing),
                FieldClock (the color field's own time, shared by screen and lights), SunClock (the
                sun by the local clock, without `sun.sun`), VistaPolicy (when the landscape takes
                the screen), ValueText (a value of one's own in words: precision, unit, on and off,
                a time of day), Lyrics (Lrc: LRC read tolerantly, tidied or raw, plain words none;
                lines with breaks; LyricsCursor: the line in the middle a little early, a small step
                back held)
text/           Texts (the words of one language: resources, plurals, numbers, percent, dates, the
                clock in 12 or 24 hours), AppLanguage + PlatformLanguageStore (LocaleManager from
                API 33, the app's own preference on 31 and 32), Localizer (StateFlow<Texts>,
                refreshed on configuration, locale and time changes), LocalTexts/texts()
color/          Oklab, CoverPalette (k-means in Oklab → ScenePalette, inks, both walls), Lamp (the
                warm daylight every color is seen in), Appearance (LIGHT / DARK, by the sun)
render/         HaloLayout (the grid, per view), CoverField (cover colors: ground + soft pools in
                the cover's colors, the veil with text shade) + FieldMotion (where each pool stands
                at a field time) + FieldModel (the field on the CPU: text shade, the lights'
                colors), WallGlow (the artwork's light on the dark wall), HaloLight (PrintShape;
                lamp tint for rest and menu), SceneBuilder (per-track precompute: Lanczos cover at
                exact px, wall, palette, flow card; relight), FlowGeometry (the shared
                perspective; the balance with cards missing), FlowCards (baked card textures),
                FlowLight (the pool of light above the cards), ImageOps
art/            ChladniArt (missing-cover art), StableHash
vista/          The landscape, a paper diorama: PaperMotif (folded mountains, forest lines, the
                clearing, the near banks, standees, birches, tent, boulders and the fire per label
                side, mirrored on the right), PaperBake* (sheets as two ink weights + the paper's
                light + coverage in boxes of their own, cut front to back with the shade of what
                stands in front, each on a Depth layer; snow caps, folds, silhouette forests,
                clustered crowns, flowers and fallen leaves, the tent; the meadow's cut-outs and
                the near banks' tall firs as Standee sprites about their feet; tissue clouds and
                flames, focus blur, shadows; its progress weighted by what each part takes), PaperGrain (pulp clouds, fibers, tooth per sheet),
                Inks/InkRaster/Coverage/Shapes (color matrices, exact AA, outlines), PaperLight
                (plates per hour: sky, key and fill light, folds, haze, fire, lantern; season
                papers, moonlight, the weather's skies and veils, mist, smoke, fireflies, label
                inks), VistaCamera (the journey: zoom and a wandering view within the paper's
                reach, CameraPose per Depth), SkyMap (south view, elevations from the skyline,
                stars by sidereal time), World (suncalc: sun, moon, phase; season by date),
                Weather + WeatherMood (state, cloud coverage and wind in its wind_speed_unit →
                cover, rain, snow, fog, storm, wind), CloudField (lanes, hashed, never repeats),
                Weatherfall (snow and rain at three depths) + MistField, VistaClock (real time; a
                day in two minutes only for measuring), Noise
lights/         LightSync (any light behind the TV via Home Assistant: a color per light, or a
                white, a brightness or just on, as the light can; saved with scene.create
                snapshot_entities and given back with scene.turn_on, our own note as a fallback;
                rate limits, takeover by others), LightColors (CoverLightColors: the cover's colors
                kept apart, placed as in the cover, moving on with motion; VistaLightColors: the
                landscape's own palette: sky, meadow or moonlit wood, mountains, sun glow, fire),
                LightColor (xy of a color's hue at its purest, grays as white (neutral by day,
                warmer at night), a color temperature for white-only lights, brightness floor)
app/            NowPlayingController (single source of truth → StateFlow<UiState>, with the texts)
                and its parts: MenuControl (the groups, then a group's settings), QuickControl (+ the
                own buttons, the party code),
                Seeking (jumps with the remote), SleepControl, LightTargets; ControllerHost
                (what the parts need), Scenes (scene cache + builds off the main thread),
                KlangbildApp/AppGraph (http, tokenHttp without redirects, localizer)
config/         Settings (+ DisplayOptions, VolumeDisplay, LightLevel, AppearanceMode, BackgroundMode,
                MotionLevel, RoomLights, RoomLightLevel) + SettingsStore (DataStore; tokens sealed by
                SecretBox: AES-GCM with a key in the Android keystore), SettingsMenu (groups,
                entries, values, explanations, steps; what needs something set up first dimmed),
                QuickMenu (the quick settings' entries and values, Lyrics On / Off / None, Party code
                On / Off, Settings),
                QuickActions (the own buttons: a name, an entity, what its kind does, a script's
                variables) + Condition (when a button shows: a state or attribute is, is not, is
                above or below a value; a list counts its entries), OwnValues (the values in the
                status line: a name, an entity, an attribute),
                LaunchExtras (am start extras and klangbild:// links: English names and values, those
                of earlier versions still taken, the German ones and `window_view`)
setup/          MiniHttp, SetupHandler (one-time code, the sign-in through Home Assistant's login
                page: state, callback, code exchange, revocation of what is not kept; API, tokens
                bound to their address, the own buttons and values, every entity and the scripts'
                fields), SetupPage (HTML in the language shown; entities chosen by searching),
                SetupServer (+ QR, discovery; Host, Origin and content type checked, a few
                connections at a time, slow ones closed, only while the setup screen shows and
                the TV has an address of its own, tried again until it has; a request that has
                arrived is still answered when it stops, checked against the address it came to;
                once a stop has returned, the port takes no connection: it waits for the thread
                blocked in accept(), which holds the socket open until it lets go)
ui/             KlangbildRoot (screen switch, OLED drift, menu overlay, peek, the quick settings'
                hint; provides LocalTexts),
                stage/ (Stage layers, Typesetting, StageTheatre transitions, ProgressBar (a range:
                the chapter), StageModel + StageTexts, FieldLayer: the pools as gradient quads,
                drawn small and scaled; Lyrics: the words in the column, set once per song off the
                main thread, one canvas, LyricsViews keeping their places through a change;
                PartyCode: the party's code where the cover stands, one picture per wall),
                Motion (+ StageTick: one frame tick for bar, field and words; animateGlide:
                a list's glide that carries on from its pace),
                OffMain (bitmaps off the main thread, at once in stills),
                flow/ (FlowStrip: cards as quads, a sign in the middle card's place; FlowTheatre:
                keyed strip transitions),
                theme/Type (Jost + DM Sans, `titleType()`/`textType()`, Figures, ink shift),
                MenuScreen (+ MenuGrid; two levels), QuickBar (+ QuickGrid; a long row glides),
                RestScreen, SetupScreen, Changes (`playChange`, `RisingText`: one change at a time, hurrying,
                held lines), DebugOverlay, vista/ (Vista: bake off the main thread and ahead of
                time, 20 fps tick, season cross-fade, the weather glided in over minutes;
                VistaWaiting: the far range's skyline traced as the first bake goes; PaperView: sky, far
                range, clearing and near banks as layers redrawn only when the light moves on,
                each only as tall as its paper, moved by the camera as a whole; stars, clouds,
                mist, snow and rain, swaying cut-outs and firs, the fire, its smoke, sparks and
                light, fireflies every frame; VistaScreen + VistaLabel: the mini player on the
                near bank, standing still while the camera moves)
dream/          KlangbildDream (DreamService with ComposeView)
test only       SyntheticCovers + Catalogue (generated test covers and tracks, test://cover/NAME, a
                book and an episode, invented words, the README's aurora), Fixtures, TestTexts, the static stages for stills
```

## Design principles (do not regress)

UI words are given in English with the German in parentheses where it matters.

- **Object, not app, but a well-made one.** No buttons, no icons, no chips, no harsh white.
  The artwork is the heaviest element and always the tallest: the text block beside it takes
  at most 62% of its height (`BLOCK_SHARE`, measured from the first cap height to the last
  baseline, never from text boxes).
- **Centered, measured on what is seen** (the user checks this; `CentringTest` measures rendered
  pixels in both languages): the stage grid (960 × 540 dp, `HaloLayout`) is artwork 368 dp at
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
  rest clock keeps natural figures and dissolves into each new minute. Neither face has
  U+202F, U+2009 or U+2002: `Texts` turns the platform's narrow spaces into U+00A0.
  Titles ≤ 3 lines, prefer breaks after section dashes, no mid-word breaks
  (`breaksInsideWord`), a word of one to three letters stays with the next ("Spiegel / im
  Spiegel"); artists on one line (smaller, then "and others" (u. a.)); the year leaves the
  album line before anything is cut; "Up next" drops the artist before it cuts; edge emoji
  dropped (`Emoji.trimEdges`), inner ones dimmed; one separator `TimeLine.SEPARATOR`;
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
  `NoFrameTest` checks rendered pixels). A wall change cross-fades the stage in 2.4 s; every
  ground reaches beyond the screen's edges (`BEYOND_EDGES_DP`), so no strip of the old wall
  ever shows at a side (`EdgesTest`).
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
  the fill dims. Hidden by setting (beside the song's words always), back on a key press.
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
  Screens switch by fading through dark. **One change at a time:** what arrives during a
  change waits until it has landed, then the newest goes next (`snapshotFlow … conflate()`).
  Only another *song* hurries the running change (`playChange`, ≤ 350 ms); a card joining the
  strip, a queue update or a better cover just wait. Lines on their way up stop where they
  are when a change hurries and leave from there (`RisingText`): the skipped song's lines
  never come up; if the skip comes to nothing (back to the same song), they rise the rest of
  the way.
- **Cover Flow** (`StageView.FLOW`): middle cover 300 dp centered 303 dp from the left and
  vertically, two cards per side turned 60° in one shared perspective (outer edges 222.5/255 dp
  beside the center, 92% height), mirrored on a glossy floor; column from 598 dp, 314 dp wide,
  48 dp left of the outermost card and right of the column; every other rule holds. A card is
  one textured quad (print, corners and, on the dark gloss only, its reflection baked; a
  half-size copy against shimmer, since HWUI has no mipmaps). Side cards at 55% / 40%, and on
  the dark wall a much lighter card steps back further beside a dark middle one. Dark: a soft
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
  to the cards to come, or the single view's 64 dp to the middle card when none come; the full
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
  under Information, remembered like the view; Music Assistant only, nothing else to set up): a
  view of the text column,
  not a screen. The line sung now stands centered on the artwork (first cap height to last
  baseline, `CentringTest`) in the title's ink; the lines around it in the quiet ink, each
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
  or off, and words that come late, use the stage's text change (`Change.WORDS`); not in the
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
  edge, rising 22 dp as it fades in, where the key points: View (Single cover / Cover Flow /
  Landscape, taken 0.9 s after the last press so no view in between shows), Lyrics On / Off, or
  None while this song has no synced words (only with Music Assistant connected and a song on
  stage), Party code On / Off (only while a party plays here), Favorite (with Music Assistant),
  Lights On / Off (off remembers the level), Shuffle On / Off (only where the player can
  shuffle; the sleep timer lives in the menu), then the own buttons, and last Settings: Open,
  the way into all settings. Left and right choose, OK changes, down, up or back close, 20 s
  alone close them. Each entry is its name (15 sp, quiet) over its value (20 sp), on the stage
  grid from 88 dp; its place is as wide as its widest value (only the last is as wide as what it
  says), so nothing moves when a value changes; the chosen one lit from below like the menu's
  line. A row longer than the room (in German the built-in entries alone are) glides to show the
  chosen entry whole, entries beyond its ends faded out; every entry fits the room on its own
  (`LanguageTest`). An opaque veil (black, paper) from 448 dp down, softened above: nothing
  behind shows through. They leave the landscape as it was. Until they have been opened once
  (`Settings.quickSeen`), the stage tells how: 3 s after it comes, for 12 s, its bottom line
  says "Arrow down opens the quick settings" (Pfeil runter öffnet die Schnelleinstellungen) in
  the place and style of "Up next" (`QuickHintTest`); not with "Up and down: Volume", not beside
  the song's words.
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
  pixels in both views, on both walls, on the cover's colors and in a pause.
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
  wraps), closes itself after 60 s. Every setting is always there: one that cannot work until
  something is set up on the phone (Lyrics without Music Assistant: "Needs Music Assistant"; the
  lights and the values while none are chosen: "Set up") is dimmed, never brighter when chosen
  than another line at rest, its explanation says what it needs, and OK opens the setup. Options
  show or hide parts, choose the view, the wall and the language, and dim the light; no fonts,
  colors or layout variants. Labels are the common words people know from TVs and phones, values
  plain words: groups Picture (View: Single cover / Cover Flow, Appearance, Background: Plain /
  Cover colors, Intensity, Motion: Off / Slow / Medium / Fast), Information (Clock, Progress
  bar, Next track, Album and year, Speaker group, Volume, Values, Lyrics), Lights (Brightness:
  Off / 10 to 100% with a slider, Lights that are off: Turn on / Leave off), Landscape (When
  paused, While playing, Mini player: Bottom left / Bottom right / Off), Remote (Control music,
  Up and down: Landscape, menu / Volume, Audiobooks, podcasts: Jump / Change track), Timer
  (Sleep timer, Turn off after, When turning off: Close app / Screen saver), System (Home
  Assistant, Music Assistant, Language: System language / English / Deutsch). Label + value fit
  the 352 dp column on one line in both languages (`LanguageTest` measures every value).
- **Language:** English is the default, German in `values-de`. The menu's Language line uses
  Android's per-app language (`LocaleManager` from API 33, where the choice also shows in the
  system's app settings; the app's own preference on API 31 and 32) and applies at once
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
  of field time, resting 70% of it). In the landscape (`VistaLightColors`) its own palette:
  above the TV its sky (the weather's too), on the side away from the fire the clearing's green
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
  the scenes are deleted. At most one call per light a second, none for invisible changes, a
  new song after 1.5 s (quick skips cost nothing) in a 2.5 s fade, drift every 10 s in 8 s
  fades. A light someone else switches off, sets to white or changes a lot is theirs until the
  next session. The lights' states arrive a moment after they are followed: taking over waits
  for them (≤ 10 s) and a light that appears later joins in; a failed call is retried once,
  for that light only. "Lights that are off" (Lichter, die aus sind): Turn on (the default, as
  above) or Leave off: then a light that is off is never switched on and not saved; one switched
  on during the music is saved and joins in, one switched off is let go and joins in again when
  switched on, and after the music each is as it was before it first joined. Leave off chosen
  while they glow: the lights that were off go back off, the others keep their colors.
- **Skip flicker** (`FlipGuard`): a return to an item left within 5 s is held 2 s and shown
  only if it is still what plays; new items pass at once, and so does the remote's "previous".
- **A song arrives whole** (`HaSource.awaitsDetails`): Home Assistant reports a Music Assistant
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
- **Landscape** (Landschaft, `vista/`; the window view until 6.3): a diorama of real paper, not
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
  (`VistaCamera`) journeys: zoom 1.01 to 1.11 about the screen's middle, the view wandering as
  far as the paper reaches at that zoom (±6 dp drift on top), but never so far that the near
  bank would leave the label (`BANK_MARGIN`); a swing in about two minutes, sums of sines in the
  golden ratio; each layer follows by its `Depth` (sky 0.15, far range 0.35, clearing 0.65, near
  banks 1), so the parallax is plain to see. The label stands still: it is no part of the
  scenery (riding the bank's scale and pan, its text shimmered in 5.2); the camera keeps its
  bank under it. No edge and no gap between layers ever shows (`VistaCameraTest` renders every
  corner of the range, `VistaCameraJourneyTest` checks six hours of poses, the bank under the
  label included); stills show it at rest. No preview in the menu (removed in 5.2: never build
  features nobody asked for); the time-lapse is only the `landscape=timelapse` extra for
  measuring. Comes after the pause delay (default 3 min; the rest clock first), during music
  only if set, never over trouble, setup or the menu, nor by itself while a party's code shows;
  up on the remote or the quick settings (View) bring it whatever plays, until it is left (up
  again, back, or another view there). Every key starts the delays again; one that came by
  itself goes with any key, which still does its work. Baked ahead only while it shows or is on
  its way (`VistaPolicy.onItsWay`); the bake is let go 10 s after it stopped coming and when the
  app goes to the background (tens of megabytes at 4K). While a first bake keeps one waiting
  (after 1 s), the far range's own skyline stands in the screen's middle as one thin line, drawn
  like the progress bar in the rest screen's quiet ink and traced from the left as the paper is
  cut (the bake's progress weighted by what each part takes, gliding, never going back, its head
  breathing); once the picture is ready it is drawn to its end and gone while the landscape
  comes up (`VistaWaiting`; a turning ring read as too plain in 6.1). The label (cover 56 dp,
  Jost 24 / DM Sans 17, bar) lies on the dark near bank (snow only along its edge), the fire on
  the other side; beside the cover one block exactly centered on it: the title's cap height to
  the bar's lower edge (filling the cover's height), or without a bar (a station) to the last
  baseline (`VistaLabelTest`). Its inks come from the bank and keep 4.5:1 on its lightest grain
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
