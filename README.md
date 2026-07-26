# WebDisplaysTogether — Server-Rendered Web Screens

**English** | [中文](#webdisplaystogether--服务器统一渲染网页屏幕)

Inspired by and descended from [WebDisplays](https://github.com/montoyo/webdisplays) by **montoyo** (via the [CinemaMod](https://github.com/CinemaMod/webdisplays) fork), for **Minecraft 1.20.1 Forge** — rebuilt around **server-side rendering**: the web browser runs on the server and every player receives the same picture as a live video stream.

## Why server-side rendering?

In the original WebDisplays every client runs its own Chromium browser, so:

- Each player sees a slightly different page (animation progress, random content, login state);
- Sites that need an account must be logged into by every player separately.

This fork moves the browser to the **server**:

- The server runs one headless Chromium (CEF) browser per screen;
- Frames are encoded as **VP9/VP8** video (Opus for audio) and streamed to nearby subscribed players;
- Clients only decode and display, and forward mouse/keyboard input back to the server;
- **Every player sees exactly the same picture**; logins are shared server-wide; playback is synchronized.

In single player the integrated server and the client share one JVM, so frames take a direct in-memory path (no encode/decode cost). Other players joining over LAN receive the video stream.

## Highlights

- Automatic codec selection (VP9 when available, VP8 fallback) and automatic bitrate/resolution
- Adaptive quality: resolution scales between 360p and 720p based on measured encoder load **and** per-viewer delivery feedback (AIMD) — the stream settles just below whatever the bottleneck is, on any server, without manual tuning
- Steady 30 fps pacing with a client-side jitter buffer for smooth playback
- Opus screen audio with positional speakers, plus automatic background-music ducking
- Incognito mode by default: cookies/logins live in memory only and are wiped on shutdown

## Installation

### Client / single player

1. Install Forge 1.20.1 (47.2.0+).
2. Install [MCEF](https://modrinth.com/mod/mcef) (CinemaMod MCEF 2.x for 1.20.1).
3. Drop this mod's jar into `mods`.

FFmpeg (video/audio codecs) is bundled inside the jar; nothing else to install.

### Dedicated server

1. Install the Forge 1.20.1 server and this mod (MCEF jar is required on the server too; it only provides the java-cef classes there).
2. On first start the server downloads the java-cef / Chromium natives (~100–200 MB) into `mods/mcef-libraries/`. The mirror is configurable (`jcef_download_mirror`).
3. Supported platforms: Windows x64, Linux x64.

#### Headless Linux

CEF needs an X display even for offscreen rendering. When `DISPLAY` is not set, the mod starts a private Xvfb automatically — just install it:

```bash
# Debian/Ubuntu
sudo apt install xvfb
```

Chromium's usual shared libraries are also required (on Debian/Ubuntu roughly):

```bash
sudo apt install libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 \
    libxcomposite1 libxdamage1 libxrandr2 libgbm1 libasound2 libxkbcommon0
```

A Linux server running as root can install these automatically (`auto_install_dependencies`, on by default).

#### Memory sizing

Each active server-side browser (a screen currently being watched) costs roughly:

- Chromium render process: 150–400 MB (page dependent, video sites on the high end);
- Encoder + frame buffers: ~20–30 MB per screen.

Budget about `max_server_browsers x 300 MB` of native (non-heap) memory. Idle screens close their browser after `browser_idle_timeout` (default 300 s) and reload from the saved URL when watched again.

## Configuration

`config/webdisplaystogether_common.toml` (server) and `config/webdisplaystogether_client.toml` (client):

| Option | Default | Description |
|---|---|---|
| `stream_fps` | 30 | Frames per second streamed to viewers (paced on a fixed clock) |
| `stream_bitrate` | 0 | Target bitrate in kbit/s; 0 = automatic from the actual stream resolution |
| `stream_max_height` | 0 | Max stream resolution; 0 = automatic (startup benchmark + runtime adaptation, 360p–720p) |
| `max_server_browsers` | 8 | Max Chromium browsers kept alive on the server |
| `browser_idle_timeout` | 300 | Seconds without viewers before a browser is closed |
| `incognito` | true | Keep cookies/logins in memory only; wiped on shutdown |
| `jcef_download_mirror` | CinemaMod mirror | Mirror for the java-cef natives download |
| `extra_cef_switches` | (empty) | Extra command-line switches for the server-side Chromium |
| `auto_install_dependencies` | true | Auto-install Xvfb/Chromium libraries on root Linux servers |
| `duck_music` (client) | true | Silence Minecraft's background music while a screen plays sound |

Bandwidth: roughly 0.5–3 Mbit/s per viewer per screen depending on content and adapted quality (static pages far less). Multiple viewers each consume their own stream.

## Security note: shared sessions

**The server-side browser's login state (cookies, sessions) is shared server-wide.** Anyone allowed to interact with a screen acts with whatever is logged in. Incognito mode is on by default, so state never touches the disk and is wiped on restart — still:

- Never log into personal accounts on a public server's screen;
- Use the screen's friend/permission system (right-click the screen → permissions) to restrict who can interact;
- Server owners can restrict reachable sites with the URL blacklist.

## Scope

Core features are covered: multiblock screens, keyboard, laser pointer, upgrades, redstone, Miniserv file hosting (`webdisplays://`), `mod://` built-in pages. The minePad still renders locally on the client. ComputerCraft / OpenComputers integration has not been ported.

## Building

```bash
./gradlew build
```

Output lands in `build/libs/`. The bytedeco FFmpeg classes and the Windows/Linux x64 natives (avcodec, avutil, swscale, swresample only) are merged into the jar.

## License

MIT, following upstream WebDisplays (see `LICENSE`). This mod is inspired by [WebDisplays](https://github.com/montoyo/webdisplays) by **montoyo**; maintained along the way by the CinemaMod Group; this fork adds server-side rendering and streaming.

---

# WebDisplaysTogether — 服务器统一渲染网页屏幕

[English](#webdisplaystogether--server-rendered-web-screens) | **中文**

灵感来源于 **montoyo** 的 [WebDisplays](https://github.com/montoyo/webdisplays)（经由 [CinemaMod](https://github.com/CinemaMod/webdisplays) fork 演化而来），适用于 **Minecraft 1.20.1 Forge**，核心改动是**服务器端渲染**：浏览器在服务器上运行，所有玩家以视频流的形式收到同一份画面。

## 为什么要服务器端渲染？

原版 WebDisplays 中，每个客户端各自运行一个 Chromium 浏览器渲染网页，导致：

- 每个玩家看到的画面不完全相同（动画进度、随机内容、登录状态各不相同）；
- 需要账号的网站，每个玩家都要各自登录。

本改造版将浏览器搬到了**服务器端**：

- 服务器为每块屏幕运行一个 Chromium (CEF) 离屏浏览器；
- 画面用 **VP9/VP8** 编码成视频流（音频用 Opus），推送给附近订阅的玩家；
- 客户端只负责解码显示，以及把鼠标/键盘输入回传给服务器注入浏览器；
- **所有玩家看到的画面完全一致**，登录状态全服共享，播放进度同步。

单人模式下，集成服务器与客户端在同一 JVM，画面走内存直通路径（不经过编解码），无额外性能损耗；局域网 (LAN) 加入的其他玩家则走视频流。

## 亮点

- 自动选择编解码器（优先 VP9，回退 VP8），码率/分辨率自动计算
- 自适应画质：根据编码耗时和每个观看者的到达质量反馈 (AIMD)，在 360p–720p 之间动态调整——在任何服务器上自动稳定在瓶颈之下，无需手动调参
- 严格 30 fps 节拍发送 + 客户端抖动缓冲，保证流畅
- Opus 屏幕音频、可摆位的音响方块，屏幕播放时自动压低背景音乐
- 默认无痕模式：Cookie/登录只存内存，关服即清空

## 安装

### 客户端 / 单人

1. 安装 Forge 1.20.1（47.2.0+）。
2. 安装 [MCEF](https://modrinth.com/mod/mcef)（CinemaMod MCEF 2.x for 1.20.1）。
3. 将本 mod 的 jar 放入 `mods` 文件夹。

FFmpeg（视频/音频编解码）已打包进 mod jar 内，无需单独安装。

### 专用服务器 (Dedicated Server)

1. 安装 Forge 1.20.1 服务端与本 mod（服务端同样需要 MCEF jar 提供 java-cef 类）。
2. 首次启动时，服务器会自动下载 java-cef / Chromium natives（约 100–200 MB）到 `mods/mcef-libraries/`。镜像地址可配置（`jcef_download_mirror`）。
3. 支持平台：Windows x64、Linux x64。

#### Linux 无头环境 (Headless) 必读

CEF 即使做离屏渲染也需要一个 X display。没有 `DISPLAY` 时，本 mod 会自动启动私有 Xvfb，只需安装：

```bash
# Debian/Ubuntu
sudo apt install xvfb
```

另外还需要 Chromium 的常见运行库（Debian/Ubuntu 大致为）：

```bash
sudo apt install libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 \
    libxcomposite1 libxdamage1 libxrandr2 libgbm1 libasound2 libxkbcommon0
```

以 root 运行的 Linux 服务器可以自动安装这些依赖（`auto_install_dependencies`，默认开启）。

#### 内存预估

每个活跃的服务器端浏览器（即一块正在被观看的屏幕）大约占用：

- Chromium 渲染进程：150–400 MB（取决于页面复杂度，视频网站偏高）；
- 编码器 + 帧缓冲：每屏约 20–30 MB。

建议按 `max_server_browsers x 300 MB` 预留本机内存（JVM 堆之外）。无人观看的屏幕超过 `browser_idle_timeout`（默认 300 秒）后自动关闭浏览器，重新有人看时按保存的 URL 重新加载。

## 配置项

服务端 `config/webdisplaystogether_common.toml`，客户端 `config/webdisplaystogether_client.toml`：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `stream_fps` | 30 | 推送给观看者的视频帧率（固定节拍） |
| `stream_bitrate` | 0 | 目标码率 (kbit/s)；0 = 按实际流分辨率自动计算 |
| `stream_max_height` | 0 | 视频流最大分辨率；0 = 自动（启动基准测试 + 运行时自适应，360p–720p） |
| `max_server_browsers` | 8 | 服务器同时保持的 Chromium 浏览器上限 |
| `browser_idle_timeout` | 300 | 无人观看多少秒后关闭浏览器 |
| `incognito` | true | 无痕模式：Cookie/登录只存内存，关服即清空 |
| `jcef_download_mirror` | CinemaMod 镜像 | java-cef natives 下载镜像 |
| `extra_cef_switches` | 空 | 传给服务器端 Chromium 的额外命令行开关 |
| `auto_install_dependencies` | true | root Linux 服务器自动安装 Xvfb/Chromium 依赖 |
| `duck_music`（客户端） | true | 屏幕播放声音时压低 Minecraft 背景音乐 |

带宽：单屏幕单观看者约 0.5–3 Mbit/s（取决于内容与自适应后的画质，静态页面远低于此）。同一屏幕的多个观看者各自消耗一份带宽。

## 安全提示：共享会话

**服务器端浏览器的登录状态（Cookie、会话）是全服共享的。**任何有权操作屏幕的玩家都能以已登录的身份操作网页。默认开启无痕模式，登录状态只存内存、重启即清空，且不写入磁盘；即便如此仍请注意：

- 不要在公开服务器的屏幕上登录个人账号；
- 用屏幕的好友权限系统（右键屏幕 → 权限设置）限制谁能操作屏幕；
- 服主可通过 URL 黑名单限制可访问的网站。

## 功能范围

当前版本覆盖核心功能：屏幕（多方块）、键盘、激光笔、各类升级、红石交互、Miniserv 文件托管（`webdisplays://`）、`mod://` 内置页面。MinePad（手持平板）仍为客户端本地渲染。ComputerCraft / OpenComputers 集成暂未迁移。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`。FFmpeg (bytedeco) 的类与 Windows/Linux x64 natives（仅 avcodec、avutil、swscale、swresample）会被合并进 jar。

## 许可

MIT，沿用上游 WebDisplays（见 `LICENSE`）。本 mod 灵感来源于 **montoyo** 的 [WebDisplays](https://github.com/montoyo/webdisplays)，CinemaMod Group 曾维护，本 fork 增加服务器统一渲染与视频流。
