<img src="assets/pipevr-icon.png" alt="PipeVR icon: a pipe with a red play button and VR on it" width="128" style="image-rendering: pixelated">

# PipeVR

[![Downloads](https://img.shields.io/github/downloads/SpiritEXEWasTaken/PipeVR/total?label=downloads&color=2185A6)](https://github.com/SpiritEXEWasTaken/PipeVR/releases)
[![Stars](https://img.shields.io/github/stars/SpiritEXEWasTaken/PipeVR?color=BA1839)](https://github.com/SpiritEXEWasTaken/PipeVR/stargazers)
[![Latest release](https://img.shields.io/github/v/release/SpiritEXEWasTaken/PipeVR?label=release)](https://github.com/SpiritEXEWasTaken/PipeVR/releases/latest)
[![License: GPLv3](https://img.shields.io/github/license/SpiritEXEWasTaken/PipeVR)](LICENSE)

[![Download APK](https://img.shields.io/badge/Download-APK-2185A6?logo=android&logoColor=white)](https://github.com/SpiritEXEWasTaken/PipeVR/releases/latest/download/PipeVR-arm64.apk) [![Install with FrameDrop](https://img.shields.io/badge/Steam%20Frame-Install%20with%20FrameDrop-1b2838?logo=steam&logoColor=white)](https://framedropvr.com/install?manifest=https%3A%2F%2Fraw.githubusercontent.com%2FSpiritEXEWasTaken%2FPipeVR%2Fmain%2Fpipevr.framedrop.json)

An all-in-one video and streaming app for VR headsets (Meta Quest, Pico, Steam Frame and other Android-based headsets), built on [PipePipe](https://codeberg.org/NullPointerException/PipePipe).

No ads, SponsorBlock built in, Return YouTube Dislike, and a native VR mode.

## What it does

- **Sites:** YouTube, Twitch, Kick, Patreon, Floatplane, Rumble, Odysee, Dailymotion, Vimeo, Nebula, BiliBili and NicoNico. "Search all sites" searches all of them at once.
- **YouTube account:** TV-style code sign-in, with several accounts. Home, Subscriptions, History, Watch later, Liked and playlists. Watch history and resume sync with your other devices. Likes and dislikes, subscribing, comments and replies (including liking them), age-restricted and members-only videos.
- **Shorts mode:** looping Shorts with likes, dislikes and comments. YouTube feeds leave Shorts out, since they have their own mode.
- **VR mode:**
  - 360° and 180° video, mono or 3D. Theater screens for flat and 3D video.
  - Eight surroundings for the screen views: dark room, movie theater, space, beach at sunset, northern lights, city rooftop, underwater, and passthrough (your own room).
  - The live chat as a panel you can move and resize, and captions.
- **Live chat:** 15 minutes of history, a pop-out chat window, and sending on Twitch and Kick.
- **Captions:** YouTube's styled captions (positions, colors, fonts, effects) in the normal player and in VR. Captions also work on Vimeo, Dailymotion, Nebula, Rumble and BiliBili.

## Known limitations

These come from the sites themselves, not from bugs in PipeVR:

- **YouTube live chat can be read but not sent.** PipeVR signs in to YouTube the way the YouTube TV app does, and YouTube refuses chat messages from that kind of sign-in. Google also blocks signing in to Google inside an app's web page, so there's no other way in. Use the YouTube phone app or website to chat.
- **YouTube comments can't be deleted from PipeVR.** The YouTube TV sign-in offers no delete action for comments. Delete them on your phone or computer.
- **Following and unfollowing on Twitch happens on Twitch's own page.** Twitch only accepts follow changes from its website, so the Follow button opens the channel's Twitch page inside PipeVR, already signed in.
- **Paid content needs a membership:** Nebula, Floatplane and members-only Patreon posts. Nebula's free sample videos play without an account.
- **Rumble and Dailymotion** sometimes run a bot check. PipeVR gets past it with the system's browser engine, so the first video can take a few seconds to start.

## Installing

Download the APK from [Releases](https://github.com/SpiritEXEWasTaken/PipeVR/releases) and install it with `adb install PipeVR-*.apk`, or with SideQuest. It shows up in your headset's app library under Unknown Sources.

### Steam Frame

[![Install with FrameDrop](https://img.shields.io/badge/Steam%20Frame-Install%20with%20FrameDrop-1b2838?logo=steam&logoColor=white)](https://framedropvr.com/install?manifest=https%3A%2F%2Fraw.githubusercontent.com%2FSpiritEXEWasTaken%2FPipeVR%2Fmain%2Fpipevr.framedrop.json)

On a Steam Frame, install PipeVR with [FrameDrop](https://framedropvr.com/), a free sideloading app for the Frame:

1. Set up FrameDrop and pair it with your headset, following its [how-to](https://framedropvr.com/how-to).
2. Click **Install with FrameDrop** above. FrameDrop asks you to confirm, then installs PipeVR and adds it to your Steam library.

You can also download [PipeVR-arm64.apk](https://github.com/SpiritEXEWasTaken/PipeVR/releases/latest/download/PipeVR-arm64.apk) and drop it into FrameDrop yourself.

## Building

You need JDK 25 and the Android SDK with NDK 30.0.16248370 and CMake 3.31.6. Set `sdk.dir` in `local.properties`, then run:

```
JAVA_HOME=<JDK 25> ./gradlew assembleDebug
```

- `app/`: the Android app, including the VR renderer (`app/src/main/cpp`, OpenXR).
- `extractor/`: the site extractors (YouTube, Twitch, Kick, Patreon and the others). The app build includes it automatically.

## Credits

PipeVR is a fork of [PipePipe](https://codeberg.org/NullPointerException/PipePipe), which is based on [NewPipe](https://github.com/TeamNewPipe/NewPipe). It is licensed under the GPLv3, like both of them.
