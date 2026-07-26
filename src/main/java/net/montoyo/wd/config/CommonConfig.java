package net.montoyo.wd.config;

import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.config.annoconfg.AnnoCFG;
import net.montoyo.wd.config.annoconfg.annotation.format.*;
import net.montoyo.wd.config.annoconfg.annotation.value.Default;
import net.montoyo.wd.config.annoconfg.annotation.value.IntRange;
import net.montoyo.wd.config.annoconfg.annotation.value.LongRange;

@SuppressWarnings("DefaultAnnotationParam")
@Config(type = ModConfig.Type.COMMON)
public class CommonConfig {
	@SuppressWarnings("unused")
	private static final AnnoCFG CFG = new AnnoCFG(FMLJavaModLoadingContext.get().getModEventBus(), CommonConfig.class);

	public static void init() {
		// loads the class
	}

	@Name("hard_recipes")
	@Comment("If true, breaking the minePad is required to craft upgrades.")
	@Translation("config.webdisplaystogether.hard_recipes")
	@Default(valueBoolean = true)
	public static boolean hardRecipes = true;

	@Name("disable_ownership_thief")
	@Comment("If true, the ownership thief item will be disabled")
	@Translation("config.webdisplaystogether.disable_thief")
	@Default(valueBoolean = false)
	public static boolean disableOwnershipThief = false;
	
	@Comment("Options for the browsers (both the minePad and the screens)")
	@CFGSegment("browser_options")
	public static class Browser {
		@Name("blacklist")
		@Comment("The page which screens should open up to when turning on")
		@Translation("config.webdisplaystogether.blacklist")
		@Default(valueStr = "")
		public static String[] blacklist = new String[0];
		
		@Name("home_page")
		@Comment("The page which screens should open up to when turning on")
		@Translation("config.webdisplaystogether.home_page")
		@Default(valueStr = "mod://webdisplaystogether/main.html")
		public static String homepage = "mod://webdisplaystogether/main.html";
	}
	
	@Comment("Options for the in world screen blocks")
	@CFGSegment("screen_options")
	public static class Screen {
		@Name("max_resolution_x")
		@Comment("The maximum value screen's horizontal resolution, in pixels")
		@Translation("config.webdisplaystogether.max_res_x")
		@IntRange(minV = 0, maxV = Integer.MAX_VALUE)
		@Default(valueI = 1920)
		public static int maxResolutionX = 1920;
		
		@Name("max_resolution_y")
		@Comment("The maximum value screen's vertical resolution, in pixels")
		@Translation("config.webdisplaystogether.max_res_y")
		@IntRange(minV = 0, maxV = Integer.MAX_VALUE)
		@Default(valueI = 1080)
		public static int maxResolutionY = 1080;
		
		@Name("max_width")
		@Comment("The maximum width for the screen multiblock, in blocks")
		@Translation("config.webdisplaystogether.max_width")
		@IntRange(minV = 0, maxV = Integer.MAX_VALUE)
		@Default(valueI = 16)
		public static int maxScreenSizeX = 16;
		
		@Name("max_height")
		@Comment("The maximum height for the screen multiblock, in blocks")
		@Translation("config.webdisplaystogether.max_height")
		@IntRange(minV = 0, maxV = Integer.MAX_VALUE)
		@Default(valueI = 16)
		public static int maxScreenSizeY = 16;
	}
	
	@Comment("Options for the server-side browser & video streaming")
	@CFGSegment("server_browser")
	public static class Stream {
		@Name("stream_fps")
		@Comment("Frames per second sent to clients watching a screen. Frames are paced on a fixed clock; 30 (default) matches Chromium's offscreen paint rate for judder-free motion")
		@Translation("config.webdisplaystogether.stream_fps")
		@IntRange(minV = 1, maxV = 60)
		@Default(valueI = 30)
		public static int streamFps = 30;

		@Name("stream_bitrate")
		@Comment("Target VP8 bitrate per screen, in kbit/s. 0 (default) = automatic: picked from the actual stream resolution so playback stays smooth")
		@Translation("config.webdisplaystogether.stream_bitrate")
		@IntRange(minV = 0, maxV = 50000)
		@Default(valueI = 0)
		public static int streamBitrateKbps = 0;

		@Name("stream_max_height")
		@Comment("Maximum vertical resolution of the video stream sent to clients; bigger screens are downscaled before encoding (the browser itself keeps its full resolution, clicks are unaffected). 0 (default) = automatic: a startup encode benchmark picks the largest size this server's CPU can stream smoothly")
		@Translation("config.webdisplaystogether.stream_max_height")
		@IntRange(minV = 0, maxV = 2160)
		@Default(valueI = 0)
		public static int streamMaxHeight = 0;

		@Name("max_server_browsers")
		@Comment("Maximum number of Chromium browsers kept alive on the server at the same time")
		@Translation("config.webdisplaystogether.max_server_browsers")
		@IntRange(minV = 1, maxV = 64)
		@Default(valueI = 8)
		public static int maxServerBrowsers = 8;

		@Name("browser_idle_timeout")
		@Comment("Seconds without any viewer after which a server browser is closed (its page state is lost)")
		@Translation("config.webdisplaystogether.browser_idle_timeout")
		@IntRange(minV = 5, maxV = 86400)
		@Default(valueI = 300)
		public static int browserIdleTimeout = 300;

		@Name("incognito")
		@Comment("If true (default), server-side browsers run in incognito mode: cookies, logins and cache are kept in memory only and are wiped when the browser/server shuts down")
		@Translation("config.webdisplaystogether.incognito")
		@Default(valueBoolean = true)
		public static boolean incognito = true;

		@Name("jcef_download_mirror")
		@Comment("Mirror used by the dedicated server to download the java-cef (Chromium) natives")
		@Translation("config.webdisplaystogether.jcef_download_mirror")
		@Default(valueStr = "https://mcef-download.cinemamod.com")
		public static String jcefDownloadMirror = "https://mcef-download.cinemamod.com";

		@Name("extra_cef_switches")
		@Comment("Extra command-line switches passed to the server-side Chromium instance")
		@Translation("config.webdisplaystogether.extra_cef_switches")
		@Default(valueStr = "")
		public static String[] extraCefSwitches = new String[0];

		@Name("auto_install_dependencies")
		@Comment("If true (default), a dedicated Linux server running as root will automatically install missing system dependencies (Xvfb and Chromium's shared libraries) using the distribution's package manager")
		@Translation("config.webdisplaystogether.auto_install_dependencies")
		@Default(valueBoolean = true)
		public static boolean autoInstallDependencies = true;
	}

	@Comment("Options for the miniserver")
	@CFGSegment("mini_server")
	public static class MiniServ {
		@Name("miniserv_port")
		@Comment("The port used by miniserv. 0 to disable")
		@Translation("config.webdisplaystogether.miniserv_port")
		@IntRange(minV = 0, maxV = Short.MAX_VALUE)
		@Default(valueI = 25566)
		public static int miniservPort = 25566;
		
		@Name("miniserv_quota")
		@Comment("The amount of data that can be uploaded to miniserv, in KiB (so 1024 = 1 MiO)")
		@Translation("config.webdisplaystogether.miniserv_quota")
		@LongRange(minV = 0, maxV = Long.MAX_VALUE)
		@Default(valueL = 1920)
		public static long miniservQuota = 1024; //It's stored as a string anyway
	}
	
	@SuppressWarnings("unused")
	public static void postLoad() {
		WebDisplays.INSTANCE.miniservPort = MiniServ.miniservPort;
		WebDisplays.INSTANCE.miniservQuota = MiniServ.miniservQuota * 1024L;
	}
	
	//    //Comments & shit
//        blacklist.setComment("An array of domain names you don't want to load.");
//        padHeight.setComment("The minePad Y resolution in pixels. padWidth = padHeight * " + PAD_RATIO);
//        hardRecipe.setComment("If true, breaking the minePad is required to craft upgrades.");
//        homePage.setComment("The URL that will be loaded each time you create a screen");
//        disableOT.setComment("If true, the ownership thief item will be disabled");
//        loadDistance.setComment("All screens outside this range will be unloaded");
//        unloadDistance.setComment("All unloaded screens inside this range will be loaded");
//        maxResX.setComment("Maximum horizontal screen resolution, in pixels");
//        maxResY.setComment("Maximum vertical screen resolution, in pixels");
//        miniservPort.setComment("The port used by miniserv. 0 to disable.");
//        miniservPort.setMaxValue(Short.MAX_VALUE);
//        miniservQuota.setComment("The amount of data that can be uploaded to miniserv, in KiB (so 1024 = 1 MiO)");
//        maxScreenX.setComment("Maximum screen width, in blocks. Resolution will be clamped by maxResolutionX.");
//        maxScreenY.setComment("Maximum screen height, in blocks. Resolution will be clamped by maxResolutionY.");
}
