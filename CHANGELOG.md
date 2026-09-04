# WebDisplaysTogether — Changelog

English first, 中文在后。

> **How this file was produced.** Only versions 1.0 and 1.01 exist as git
> commits. Everything from 1.02 onward was reconstructed by diffing the
> released jars in `build/libs/` (class sets, class sizes, bundled resources
> and language keys). Feature-level entries are reliable; the finer details of
> the bug fixes inside individual classes could not be recovered and are
> summarised by area.

## Version naming

The Forge 1.20.1 line uses `1.0, 1.01, 1.02, 1.03, 1.04, 1.05, 1.06`. The
1.21.1 line first shipped as `1.0.3` and then followed the same numbers
(`1.05`, `1.06`). `1.0.3` and `1.03` are the same release. Consider
normalising to a single scheme (e.g. `1.0.6`) before publishing.

---

## 1.08

**Removed**

- **The mod no longer downloads anything, ever.** The java-cef native downloader
  in `ServerCefManager` is gone, along with every HTTP call in the codebase. A
  dedicated server owner now installs `mods/mcef-libraries` by hand. This is what
  CurseForge asks of mods that would otherwise fetch executables at runtime, and
  it also means the mod works unchanged on servers with no outbound internet
  access.
- `-Dwd.nativesHost=<url>` is gone with it — there is nothing left to mirror.
- **No consent prompt of any kind.** The `wd-natives-download.properties` file
  that 1.06 hid inside `mods/mcef-libraries/` is gone and nothing replaced it.
  With no download to approve there is nothing to consent to, so the mod simply
  works as soon as the natives are in place.

**Changed**

- **A dedicated server with no natives writes `webdisplaystogether-setup.txt`**
  next to vanilla's `eula.txt`, containing the exact archive URL for the java-cef
  commit your MCEF version expects, the SHA-256 published next to it, and the
  target directory. The same instructions go to the console. The file is
  rewritten whenever the natives are missing and can be deleted freely.
- **Missing natives are no longer treated as a startup failure.** The server
  starts and runs normally; only screens stay disabled.

**Added**

- **Linux arm64 servers are supported.** The FFmpeg/JavaCPP codec natives are now
  bundled for `linux-arm64` alongside `windows-x86_64` and `linux-x86_64`, so a
  server on AWS Graviton, Oracle Cloud Ampere A1 or a Raspberry Pi can encode
  streams. java-cef already published matching arm64 builds; the missing codecs
  were the only blocker, and previously they failed with a stack trace after the
  operator had already installed Chromium. The jar grows from 32 MB to 45 MB.
- **A clear message when the architecture is unsupported.** If the jar carries
  codec natives but none for the running machine (macOS, Windows on ARM), the log
  now names both the detected platform and the bundled ones instead of retrying a
  doomed load five times and leaving a stack trace as the only clue.
- **Private in-game reminder for operators.** An operator who places a screen
  while the natives are missing gets one private chat line pointing at the setup
  file. Non-operators are never told, and the same operator is only reminded once
  every five minutes so building a multiblock screen does not spam them.

---

## 1.06

The largest release since the initial one; 27 classes changed on every loader.

**Added**

- **Per-screen stream quality selector.** A new `ScreenQuality` setting in the
  screen configurator with four options: `Auto` (adaptive 360p–1080p),
  `Fluent` (fixed 360p), `SD` (fixed 720p) and `HD` (fixed 1080p). The server
  browser still renders at the screen's own resolution; the setting only caps
  the resolution of the encoded stream. The value is stored per screen, synced
  to clients, and settable through the screen control API.
- **Consent gate for the natives download.** When the java-cef natives are
  missing, the server no longer downloads them silently. It prints a notice,
  writes a `wd-natives-download.properties` template into
  `mods/mcef-libraries/`, and leaves screens disabled until the administrator
  approves — either via that file or with `-Dwd.acceptNativesDownload=true`.

**Changed**

- **The java-cef natives are no longer bundled in the jar.** This reverses the
  change made in 1.04: the download is back, now behind the consent gate and
  still SHA-256 verified. The jar drops from **285 MB back to 33.6 MB**.
- Fixes and adjustments across the screen block and block entity, screen data,
  the keyboard and screen-config GUIs, the screen model baker, the config
  system, the JS-request and screen-settings controls, the permission errors,
  the screen configurator item, the screen update and stream frame packets,
  the GLFW stub injector and the video encoder/decoder.

**Removed**

- The automatic-volume GUI option and its A/V warning line
  (`gui.screencfg.autovol`, `gui.screencfg.avwarning`).

---

## 1.05

Streaming-only release. Six classes in the streaming path were tuned
(`ClientScreenStream`, `ClientStreamManager`, `C2SMessageStreamCtrl`,
`ServerBrowserManager`, `StreamedScreen`, `VideoEncoder`) — adaptive bitrate
and resolution behaviour. Still shipped with the bundled natives (285 MB jar).

---

## 1.04

**Changed**

- **The java-cef natives were bundled into the jar** — `windows_amd64.tar.gz`
  and `linux_amd64.tar.gz` (~252 MB) with SHA-256 sidecar files, so a dedicated
  server needed no network access on first start. The jar grew from 33.6 MB to
  **285 MB**.
- Further streaming pipeline work in the same six classes as 1.05.

**Removed**

- The runtime native downloader (`ServerCefManager$PkgManager` and its
  `LoggingDownloadListener`), now unnecessary.
- Two config options that only made sense with the downloader:
  `jcef_download_mirror` and `auto_install_dependencies`.

---

## 1.03 (shipped as 1.0.3 on 1.21.1)

**Added**

- **Fabric 1.21.1 and NeoForge 1.21.1 ports.** First release outside
  Forge 1.20.1. The Fabric build compensates for the missing Forge event hooks
  with additional mixins and a set of small `net.neoforged.*` shim classes, so
  the shared logic stays identical across all three loaders.
- Download progress logging for the java-cef natives
  (`ServerCefManager$LoggingDownloadListener`).

**Changed**

- Streaming rework touching `ClientScreenStream`, `ClientStreamManager`,
  `C2SMessageStreamCtrl`, `ServerBrowserManager`, `ServerCefManager`,
  `StreamedScreen` and `VideoEncoder`.

---

## 1.02

Metadata-only release — the jar is **byte-for-byte identical** to 1.01. No
code or resource changed.

---

## 1.01

- **Fixed:** screens whose Chromium render process died stayed frozen and
  ignored mouse input. The screen now detects the dead render process and
  recovers automatically.
- Filled in `displayURL` and `issueTrackerURL` in the mod metadata.

---

## 1.0

Initial release, Forge 1.20.1 only. Server-rendered web screens streamed to
every player as video: one shared Chromium browser per screen, VP8/VP9 video
with Opus audio, multiblock screens, keyboards, laser pointer, upgrade
components, redstone interaction, per-screen permissions, the built-in
miniserv file host, and incognito-by-default browsing.

---
---

# 更新日志（中文）

> **本文件的来源说明。** 只有 1.0 和 1.01 保留了 git 提交记录。1.02 及之后的版本
> 是通过比对 `build/libs/` 里已发布的 jar（类列表、类大小、打包资源、语言键）
> 还原出来的。功能级别的条目是可靠的；单个类内部的具体 bug 修复无法还原，只能
> 按模块概括。

## 版本号说明

Forge 1.20.1 线用的是 `1.0、1.01、1.02、1.03、1.04、1.05、1.06`；1.21.1 线首发叫
`1.0.3`，之后跟上了同样的编号（`1.05`、`1.06`）。`1.0.3` 和 `1.03` 是同一个版本。
建议上传前统一成一种写法（比如 `1.0.6`）。

---

## 1.08

**移除**

- **模组不再下载任何东西。** `ServerCefManager` 里的 java-cef 下载器已删除，整个
  代码库中不再有任何 HTTP 调用。专用服务器的服主改为手动安装
  `mods/mcef-libraries`。CurseForge 对"运行时抓取可执行文件"的模组就是这个要求，
  同时这也让模组在没有外网的服务器上照常可用。
- 随之移除 `-Dwd.nativesHost=<url>`——已经没有下载可以换源了。
- **不再有任何同意门槛。** 1.06 藏在 `mods/mcef-libraries/` 里的
  `wd-natives-download.properties` 已删除，也没有东西取代它。既然没有下载，就没有
  什么需要同意的——运行库就位之后模组直接可用。

**变更**

- **缺少运行库时，专用服务器会在原版 `eula.txt` 旁边写一个
  `webdisplaystogether-setup.txt`**，里面是你的 MCEF 版本所需 java-cef commit 的
  确切压缩包地址、官方公布的 SHA-256 和目标目录，控制台也会打印同样的内容。
  这个文件在缺少运行库时会重新生成，可以随意删除。
- **缺少运行库不再被当作启动失败。** 服务器照常启动运行，只有屏幕保持禁用。

**新增**

- **支持 Linux arm64 服务器。** FFmpeg/JavaCPP 编解码运行库现在除 `windows-x86_64`
  和 `linux-x86_64` 之外也打包了 `linux-arm64`，AWS Graviton、Oracle Cloud Ampere
  A1 和树莓派上的服务器可以正常编码画面了。java-cef 本来就有对应的 arm64 构建，缺的
  一直只是编解码器；此前服主装好 Chromium 之后才会撞上这个报错。jar 体积从 32 MB 增至
  45 MB。
- **架构不受支持时给出明确提示。** 如果 jar 里带了运行库但没有当前机器这一份
  （macOS、ARM 版 Windows），日志现在会同时写出检测到的平台和已打包的平台，而不是
  白重试五次、只留一个堆栈给人猜。
- **给管理员的游戏内私聊提醒。** 缺少运行库时，OP 玩家放置屏幕会收到一行私聊，指向
  那个设置说明文件。普通玩家不会收到；同一个管理员五分钟内只提醒一次，搭多方块屏幕
  时不会刷屏。

---

## 1.06

首发之后改动最大的一次，三个加载器各有 27 个类发生变化。

**新增**

- **每块屏幕的清晰度选择。** 屏幕配置界面新增 `ScreenQuality` 设置，四档：
  `自动`（360p–1080p 自适应）、`流畅`（固定 360p）、`标清`（固定 720p）、
  `高清`（固定 1080p）。服务端浏览器仍按屏幕自身分辨率渲染，这个设置只限制编码
  视频流的分辨率。该值按屏幕保存、同步给客户端，也可以通过屏幕控制 API 设置。
- **natives 下载的授权确认。** java-cef natives 缺失时，服务器不再静默下载。它会
  打印提示、在 `mods/mcef-libraries/` 下写一个
  `wd-natives-download.properties` 模板，并让屏幕保持禁用，直到管理员通过该文件
  或 `-Dwd.acceptNativesDownload=true` 授权为止。

**变更**

- **jar 不再内置 java-cef natives。** 这是对 1.04 的回退：改回运行时下载，但加上
  了授权门槛，并仍然做 SHA-256 校验。jar 体积从 **285 MB 降回 33.6 MB**。

- 屏幕方块与方块实体、屏幕数据、键盘与屏幕配置 GUI、屏幕模型烘焙、配置系统、
  JS 请求与屏幕设置控制、权限报错、屏幕配置器物品、屏幕更新与流帧数据包、
  GLFW 桩注入器、视频编解码器等模块的修复与调整。

**移除**

- 自动音量的 GUI 选项及其 A/V 警告文字
  （`gui.screencfg.autovol`、`gui.screencfg.avwarning`）。

---

## 1.05

纯串流版本。串流链路上的 6 个类做了调优（`ClientScreenStream`、
`ClientStreamManager`、`C2SMessageStreamCtrl`、`ServerBrowserManager`、
`StreamedScreen`、`VideoEncoder`），涉及自适应码率与分辨率的行为。仍然内置
natives（285 MB 的 jar）。

---

## 1.04

**变更**

- **把 java-cef natives 打进了 jar** —— `windows_amd64.tar.gz` 和
  `linux_amd64.tar.gz`（约 252 MB），带 SHA-256 校验文件，这样独立服务器首次启动
  不需要联网。jar 从 33.6 MB 涨到 **285 MB**。
- 与 1.05 相同的 6 个类继续做串流链路的改动。

**移除**

- 运行时的 natives 下载器（`ServerCefManager$PkgManager` 及其
  `LoggingDownloadListener`），已无必要。
- 两个只在有下载器时才有意义的配置项：`jcef_download_mirror` 和
  `auto_install_dependencies`。

---

## 1.03（1.21.1 上发布为 1.0.3）

**新增**

- **Fabric 1.21.1 与 NeoForge 1.21.1 移植。** 首次走出 Forge 1.20.1。Fabric 版用
  额外的 mixin 和一组小的 `net.neoforged.*` 垫片类来补上缺失的 Forge 事件钩子，
  这样三个加载器之间的共享逻辑可以保持一致。
- java-cef natives 的下载进度日志（`ServerCefManager$LoggingDownloadListener`）。

**变更**

- 串流部分重做，涉及 `ClientScreenStream`、`ClientStreamManager`、
  `C2SMessageStreamCtrl`、`ServerBrowserManager`、`ServerCefManager`、
  `StreamedScreen`、`VideoEncoder`。

---

## 1.02

只改了版本号 —— jar 与 1.01 **逐字节完全相同**，代码和资源都没有变化。

---

## 1.01

- **修复：** Chromium 渲染进程崩溃后屏幕会卡住并且不响应鼠标。现在屏幕会检测到
  渲染进程已死并自动恢复。
- 补上了 mod 元数据里的 `displayURL` 和 `issueTrackerURL`。

---

## 1.0

首发版本，仅支持 Forge 1.20.1。服务端渲染网页屏幕并以视频流推送给所有玩家：
每块屏幕一个共享 Chromium 浏览器、VP8/VP9 视频加 Opus 音频、多方块屏幕、键盘、
激光笔、升级组件、红石交互、按屏幕划分的权限、内置的 miniserv 文件托管，以及
默认开启的无痕浏览。
