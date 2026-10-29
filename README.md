# Klangbild

<picture>
  <source media="(prefers-reduced-motion: no-preference)" srcset="docs/screenshots/film.avif" type="image/avif">
  <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/mockup-now-playing.jpg">
  <img src="docs/screenshots/mockup-now-playing-light.jpg" alt="The album cover on a TV next to the song that's playing">
</picture>

Klangbild is an Android TV app that shows what's playing, and it's mostly there to look nice. The idea is to have it running in the background, where it just adds a bit to the mood.

<a href="https://github.com/hazymorning/Klangbild/releases/latest/download/klangbild.apk"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/download.png"><img src="docs/screenshots/download-light.png" width="236" alt="Download for Android TV"></picture></a>

## What it does

It works with any speaker that's set up in Home Assistant, but it's mostly meant to be used with Music Assistant, since that lets it also show the queue, lyrics and audiobook chapters. It can show what's playing in a few different ways, the music itself still gets picked in whatever app is already in use.

![The same song twice, on the light background by day and on the dark one at night](docs/screenshots/day-and-night.jpg)

**A few other things it can do:**

✓ HA actions as quick settings<br>
✓ HA entity values on the screen<br>
✓ Lights in the colors of the cover<br>
✓ Music on the TV itself (Sendspin)

## More

<details>
<summary><b>Compatibility</b></summary>
<br>

Klangbild needs Android TV 9 or later, or a streaming stick whose system is built on Android 9 or newer. This means older fire TV sticks probably won't work.

Since the TV can stay on for hours, the whole picture drifts slowly by a few pixels, which helps keep an OLED screen from burning in.

</details>

<details>
<summary><a name="setup"></a><b>Setting it up</b></summary>
<br>

You need Home Assistant and a TV or box with Android TV 9 or later.

<details>
<summary><b>1. Install Klangbild on your TV</b></summary>
<br>

Klangbild isn't on Google Play, so you'll need to install the APK from the [latest release](https://github.com/hazymorning/Klangbild/releases/latest) yourself.

</details>

<details>
<summary><b>2. Connect it to Home Assistant</b></summary>
<br>

The first time you open Klangbild, it shows a QR code. Scan it with your phone, sign in to Home Assistant and choose the speaker your music plays on. Everything happens on the phone, because no one likes typing on a remote. On the same page you can add Music Assistant, the lights behind your TV and a few buttons of your own for the quick settings, but all of that can wait.

> **Important**<br>
> If Music Assistant plays your music, your speaker usually shows up twice in the list, once from its own integration and once from Music Assistant. Choose the one marked Music Assistant, since only that one brings the queue, the lyrics and everything else Music Assistant knows about the song.

</details>

<details>
<summary><b>3. Open it automatically (optional)</b></summary>
<br>

If your TV is connected to Home Assistant through the Android Debug Bridge integration, an automation like this one opens Klangbild whenever your speaker starts playing:

```yaml
triggers:
  - trigger: state
    entity_id: media_player.living_room
    to: playing
actions:
  - action: media_player.select_source
    target:
      entity_id: media_player.tv
    data:
      source: io.github.hazymorning.klangbild
```

The two entity IDs are only examples, so put in the ones for your own speaker and TV.

</details>
<br>

</details>

<details>
<summary><b>TV remote</b></summary>
<br>

| Button | Action |
| --- | --- |
| OK | Play or pause (hold to open the settings) |
| Left / Right | Previous or next song (hold to seek) |
| Up | Open the settings |
| Down | Open the quick settings |

> In the settings you can change what some of the buttons do, for example to have up and down change the volume.

</details>

<details>
<summary><b>The queue</b></summary>
<br>

<table>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/screenshots/mockup-queue-off.jpg" alt="The queue off: one large album cover on its own colors, on a TV in a room lit by a lamp and candles"><br>
      <b>Off</b>
    </td>
    <td width="33%" valign="top">
      <img src="docs/screenshots/mockup-queue-list.jpg" alt="The queue as a list: the next songs with their small covers beside the album cover, on a TV in a room lit by a lamp and candles"><br>
      <b>List</b>
    </td>
    <td width="33%" valign="top">
      <img src="docs/screenshots/mockup-cover-flow.jpg" alt="The queue in Cover Flow: the covers side by side, on a TV in a room lit by a lamp and candles"><br>
      <b>Cover Flow</b>
    </td>
  </tr>
</table>

Press the down button on the remote to open the quick settings, where you can choose how much of your queue you see. The list only works once you've added Music Assistant during setup, and until then Cover Flow mostly shows the covers of songs you've already heard.

</details>

<details>
<summary><b>Sendspin</b></summary>
<br>

With Music Assistant 2.10.7 or later, the TV can become one of its speakers and play in time with the others over Sendspin. Once "Play here" is on, under System in the settings or with the box on the setup page, it shows up in Music Assistant under its own name while Klangbild is open. Set to "Always", it keeps playing even while Klangbild is closed.

> **Important**<br>
> "Play here" makes the TV a speaker, but Klangbild keeps following the speaker chosen on the setup page. To have it follow the TV, open Home Assistant under System in the settings, scan the code, choose "This TV" as the speaker and save.

</details>

<details>
<summary><b>Data privacy</b></summary>
<br>

Klangbild has no accounts and doesn't track you. Your Home Assistant login is stored encrypted on the TV and never goes anywhere but your own Home Assistant.

It asks GitHub for a new version only when you press OK on Update under System in the settings. If you turn on Check for updates there, it also asks when it starts, at most once a day.

</details>
