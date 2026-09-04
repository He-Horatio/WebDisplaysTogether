# WebDisplaysTogether — Forge 1.20.1

Browsable web pages on screens in Minecraft, rendered **on the server** and
streamed to every player as video, so **everyone sees exactly the same picture**.

This is the Forge 1.20.1 build. The same mod is also available for
Fabric 1.21.1 and NeoForge 1.21.1; all three are feature-identical.

## What it does

- **One shared browser per screen.** The page runs in a headless Chromium
  instance on the server. Every player sees the identical frame, logins are
  shared, and playback stays in sync.
- **Adaptive video.** VP8/VP9 video and Opus audio, 30 fps, resolution scaled
  automatically between 360p and 1080p from server load and per-viewer network
  feedback. Viewers can also pin a quality manually.
- **Screen audio.** Sound can come from the screen itself, from placeable
  speaker blocks positioned in 3D, or straight to the player. Minecraft's
  background music ducks automatically while a screen is playing.
- **Full screen gameplay.** Multiblock screens from 2x2 up to 16x16, keyboards,
  a laser pointer for remote clicking, upgrade components, redstone
  interaction, a per-screen permission system, and a built-in file host.
- **Incognito by default.** Cookies and logins stay in memory and are wiped
  when the server stops.

## Requirements

| | |
|---|---|
| Minecraft | 1.20.1 |
| Loader | Forge 47.2.0 or newer |
| Java | 17 |
| Required mod | [MCEF](https://www.curseforge.com/minecraft/mc-mods/mcef) 2.1.1+ (client **and** server) |
| Platforms | Windows x64, Linux x64 (no macOS build yet) |

A dedicated Linux server also needs the usual Chromium runtime libraries
(`libnss3`, `libatk1.0-0`, `libgbm1`, ...) and either an X server or `Xvfb`,
which the mod will start on its own if it finds it.

## First start on a dedicated server

The mod contains no networking code of its own and never downloads anything, so
one thing is up to the server owner: installing Chromium.

The java-cef natives (~120 MB) are **not** bundled in the jar. Download the
archive for your platform from the MCEF distribution host and extract it into
`mods/mcef-libraries/<platform>/`. The exact URL for the java-cef commit your
MCEF version expects, its published SHA-256 and the target path are printed to
the console and written into `webdisplaystogether-setup.txt` next to vanilla's
`eula.txt`. These are the same files MCEF installs on clients, so copying
`mods/mcef-libraries` off a machine that has already run the client works just
as well.

Until then the server starts and runs normally — only screens stay disabled, and
an operator who places one gets a private chat reminder. There is no EULA and no
consent prompt.

Single player and LAN worlds need none of this: they reuse the host client's
MCEF runtime.


## Building

```
./gradlew build
```

The jar lands in `build/libs/`. The build merges the bytedeco JavaCPP + FFmpeg
classes and natives for `windows-x86_64` and `linux-x86_64` into the mod jar,
and copies `LICENSE`, `NOTICE` and `licenses/` into `META-INF/`.

Useful flags:

- `./gradlew runClient -PquickPlay=host:port` — join a server directly
- `./gradlew runClient -PstreamDebug` — per-second streaming diagnostics
- `./gradlew runServer -PcefSelfTest` — validate the CEF pipeline headlessly

## Configuration

Two files under `config/`:

- `webdisplaystogether-common.toml` — screen size and resolution caps, stream
  fps/bitrate/height, concurrent browser limit, idle timeout, incognito, URL
  blacklist, home page, extra Chromium switches, miniserv port and quota.
- `webdisplaystogether-client.toml` — render distance, MinePad resolution,
  music ducking, keyboard camera, laser pointer button swap.

## Credits and license

Derived from [WebDisplays](https://github.com/montoyo/webdisplays) by
**montoyo**, released by its author into the public domain — thanks to the
original author and to the [CinemaMod](https://github.com/CinemaMod/webdisplays)
maintainers.

The idea for this fork was conceived jointly by Minecraft player **Yu_lanr**
and the author. AI assistance was used during development.

This project's own code is MIT licensed — see [LICENSE](LICENSE). Third-party
components redistributed in or used by the mod (FFmpeg under LGPL v2.1+,
JavaCPP under Apache 2.0, MCEF, CEF, jQuery) are documented in
[NOTICE](NOTICE), with full license texts in [`licenses/`](licenses).
