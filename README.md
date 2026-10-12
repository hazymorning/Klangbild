# Klangbild

Klangbild is a now playing screen for Android TV that works with Home Assistant.

[![Android TV 12+](https://img.shields.io/badge/Android%20TV%2012%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#setup)
[![Home Assistant](https://img.shields.io/badge/Home%20Assistant-18BCF2?style=flat-square&logo=homeassistant&logoColor=white)](https://www.home-assistant.io)

![Klangbild on a TV at night, the wall behind it lit in the colors of the cover](docs/screenshots/mockup-now-playing.jpg)

When you're listening to music at home, the TV usually just sits there. Switched off, it's a big black screen that's hard to ignore, and switched on, it's bright and busy in a way that doesn't suit the music. Klangbild gives it something calm to show instead.

<a href="https://github.com/hazymorning/Klangbild/releases/latest"><img src="docs/screenshots/download.png" width="236" alt="Download for Android TV"></a>

## What it does

Klangbild works with any media player in Home Assistant and shows the album cover, the song and what's coming up next. With Music Assistant it can also show the queue, synced lyrics, and chapters for audiobooks and podcasts. Klangbild can also color smart lights behind the TV to match the album cover.

The background is light during the day and dark at night, and the picture slowly shifts by a few pixels to help prevent burn-in on OLED screens. When nothing has played for ten minutes, Klangbild lets the TV go to its screen saver or standby.

![The same song twice: on the light background by day and on the dark one at night](docs/screenshots/day-and-night.jpg)

## Views

<table>
  <tr>
    <td width="33%" valign="top">
      <img src="docs/screenshots/mockup-single-cover.jpg" alt="The Single cover view: a song on its own colors, on a TV in a room lit by a lamp and candles"><br>
      <b>Single cover</b>
    </td>
    <td width="33%" valign="top">
      <img src="docs/screenshots/mockup-cover-flow.jpg" alt="The Cover Flow view: the covers of the queue side by side, on a TV in a room lit by a lamp and candles"><br>
      <b>Cover Flow</b>
    </td>
    <td width="33%" valign="top">
      <img src="docs/screenshots/mockup-landscape.jpg" alt="The landscape on a winter night, stars and a campfire, on a TV in a room lit by a lamp and candles"><br>
      <b>Landscape</b>
    </td>
  </tr>
</table>

Single cover shows one large album cover next to the song details, and Cover Flow shows the covers in your queue side by side. Landscape is a paper-cut style scene of mountains, a forest and a campfire, with a small player in the corner. You can switch views in the quick settings, or press up on the remote to go straight to the landscape.

<details>
<summary><b>More about the landscape</b></summary>
<br>

The landscape changes with the time of day and the seasons at your location, so you'll see morning light, a sunset or stars depending on when you look. If you have a weather entity in Home Assistant, it also shows the current weather, so you'll see rain on screen when it's raining outside.

By default, the landscape comes up by itself after three minutes without music. You can change that or turn it off in the settings.

</details>

## Setup

You need a TV with Android TV 12 or later and Home Assistant.

<details>
<summary><b>1. Install Klangbild on your TV</b></summary>
<br>

Klangbild isn't on Google Play, so you have to install the APK from the [latest release](https://github.com/hazymorning/Klangbild/releases/latest) yourself. The easiest way is the free Downloader app. Open the release page in Downloader, select the APK and allow the installation from unknown sources when Android asks. If you have developer options enabled, `adb install` works too.

</details>

<details>
<summary><b>2. Connect it to Home Assistant</b></summary>
<br>

When you open Klangbild, it shows a QR code. Scan it with your phone, sign in to Home Assistant and choose the speaker you want Klangbild to show. The whole setup happens on your phone, so you don't need to type anything with the remote.

You can set up Music Assistant, your lights and custom buttons on the same page, either right away or later.

</details>

## Remote

| Button | Action |
| --- | --- |
| OK | Play or pause (hold to open the settings) |
| Left / Right | Previous or next song |
| Up | Switch to the landscape and back |
| Down | Open the quick settings |

In the quick settings you can switch views and add your own Home Assistant buttons, for example one that groups all your speakers.

## Privacy

Klangbild doesn't have accounts or tracking. Your Home Assistant login is stored encrypted on the TV and is only sent to your own Home Assistant.

## License

Klangbild is licensed under the MIT license. The name is German and roughly means "sound picture".
