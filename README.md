# Klangbild

Klangbild is a now playing screen for Android TV that works with Home Assistant.

[![Android TV 9+](https://img.shields.io/badge/Android%20TV%209%2B-182B52?style=flat-square&logo=android&logoColor=A3DEF8)](#devices)
[![Home Assistant](https://img.shields.io/badge/Home%20Assistant-182B52?style=flat-square&logo=homeassistant&logoColor=A3DEF8)](https://www.home-assistant.io)

<picture>
  <source media="(prefers-reduced-motion: no-preference)" srcset="docs/screenshots/film.avif" type="image/avif">
  <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/mockup-now-playing.jpg">
  <img src="docs/screenshots/mockup-now-playing-light.jpg" alt="The album cover on a TV next to the song that's playing">
</picture>

When you're listening to music at home, the TV usually just sits there. Switched off, it's a big black screen that's hard to ignore, and switched on, it's bright and busy in a way that doesn't suit the music. Klangbild gives it something calm to show instead.

<a href="https://github.com/hazymorning/Klangbild/releases/latest/download/klangbild.apk"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/download.png"><img src="docs/screenshots/download-light.png" width="236" alt="Download for Android TV"></picture></a>

## What it does

Klangbild works with any media player in Home Assistant and shows the album cover with the song's title and artist beside it. Music Assistant adds what's coming up next, lyrics that follow along as the song plays, and chapters for audiobooks and podcasts. And if Home Assistant controls the lights behind your TV, they can take on the colors of the album cover.

During the day the background is light, and at night it turns dark. Since the TV can stay on for hours, the whole picture also drifts slowly by a few pixels, which helps keep an OLED screen from burning in.

![The same song twice: on the light background by day and on the dark one at night](docs/screenshots/day-and-night.jpg)

There's also a landscape you can switch to, and it changes with the time of day, the seasons and the weather outside.

> [!NOTE]
> **Klangbild is being built in two phases.** The first, the now playing screen, is practically finished. The second is a modern music visualizer, calm rather than psychedelic, which will one day take the landscape's place. Ideas are welcome in the [Discussions](https://github.com/hazymorning/Klangbild/discussions), and if you'd like the landscape to stay, you can say so in the [poll](https://github.com/hazymorning/Klangbild/discussions/51).

<a href="https://github.com/hazymorning/Klangbild/discussions/51"><picture><source media="(max-width: 640px) and (prefers-color-scheme: dark)" srcset="docs/screenshots/poll-small.png"><source media="(max-width: 640px)" srcset="docs/screenshots/poll-small-light.png"><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/poll.png"><img src="docs/screenshots/poll-light.png" alt="Should the landscape stay? Vote in the poll"></picture></a>

## More

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
<summary><b>The landscape</b></summary>
<br>

![The landscape on a winter night, stars and a campfire, on a TV in a room lit by a lamp and candles](docs/screenshots/mockup-landscape.jpg)

You'll see morning light, a sunset or stars depending on when you look, and if Home Assistant has a weather entity, it rains on screen when it rains outside. The up button on the remote takes you there at any time, and by default the landscape also comes up by itself after three minutes without music, which you can change or turn off in the settings.

</details>

<details>
<summary><b>Setup & automation</b></summary>
<br>

You need Home Assistant and a TV or box with Android TV 9 or later.

<details>
<summary><b>1. Install Klangbild on your TV</b></summary>
<br>

Klangbild isn't on Google Play, so you'll need to install the APK from the [latest release](https://github.com/hazymorning/Klangbild/releases/latest) yourself. The easiest way is to open the release page in the free Downloader app, select the APK and allow installs from unknown sources when Android asks.

</details>

<details>
<summary><b>2. Connect it to Home Assistant</b></summary>
<br>

The first time you open Klangbild, it shows a QR code. Scan it with your phone, sign in to Home Assistant and choose the speaker your music plays on. Everything happens on the phone, because no one likes typing on a remote. On the same page you can add Music Assistant, the lights behind your TV and a few buttons of your own for the quick settings, but all of that can wait.

</details>

<details>
<summary><b>3. Optional: let Home Assistant open Klangbild when the music starts</b></summary>
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

</details>

<a name="devices"></a>
<details>
<summary><b>Device support</b></summary>
<br>

Klangbild runs on TVs and boxes with Android TV 9 or later. Versions 9 to 11 haven't been tried on a real TV yet, so if something looks wrong on one of them, please [open an issue](https://github.com/hazymorning/Klangbild/issues/new?template=bug_report.yml).

<details>
<summary><b>Streaming sticks with Fire OS or Vega OS</b></summary>
<br>

Many streaming sticks run Fire OS, which is built on Android, and the Android version underneath most likely decides whether Klangbild installs. Fire OS 7 and later are built on Android 9 or newer, which should be enough, though Klangbild hasn't been tried on one of those sticks yet. On Fire OS 6 and earlier it won't install, probably because their Android is too old. The newest sticks run Vega OS, which isn't Android at all, so Android apps most likely won't install on them.

</details>

</details>

<details>
<summary><b>TV remote</b></summary>
<br>

| Button | Action |
| --- | --- |
| OK | Play or pause (hold to open the settings) |
| Left / Right | Previous or next song |
| Up | Switch to the landscape |
| Down | Open the quick settings |

In the settings you can change what some of the buttons do, for example to have up and down change the volume.

</details>

<details>
<summary><b>Contributing</b></summary>
<br>

Klangbild is a small project made for the joy of it, and any help is welcome. A bug fix or a translation can go straight into a pull request, while bigger ideas, like bringing it to another kind of TV, are easier to talk over in a discussion first.

</details>

<details>
<summary><b>Privacy</b></summary>
<br>

Klangbild has no accounts and doesn't track you. Your Home Assistant login is stored encrypted on the TV and never goes anywhere but your own Home Assistant.

</details>
