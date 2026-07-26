/*
 * Copyright (C) 2019 BARBOTIN Nicolas
 */

package net.montoyo.wd;

import com.google.gson.Gson;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.montoyo.wd.client.ClientProxy;
import net.montoyo.wd.client.gui.camera.KeyboardCamera;
import net.montoyo.wd.config.ClientConfig;
import net.montoyo.wd.config.CommonConfig;
import net.montoyo.wd.controls.ScreenControlRegistry;
import net.montoyo.wd.core.*;
import net.montoyo.wd.miniserv.server.Server;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.client_bound.S2CMessageServerInfo;
import net.montoyo.wd.registry.BlockRegistry;
import net.montoyo.wd.registry.ItemRegistry;
import net.montoyo.wd.registry.TileRegistry;
import net.montoyo.wd.registry.WDTabs;
import net.montoyo.wd.utilities.DistSafety;
import net.minecraft.core.BlockPos;
import net.montoyo.wd.utilities.Log;
import net.montoyo.wd.utilities.serialization.Util;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

@Mod("webdisplaystogether")
public class WebDisplays {
    public static WebDisplays INSTANCE;

    public static SharedProxy PROXY = null;
    
    public static final ResourceLocation ADV_PAD_BREAK = new ResourceLocation("webdisplaystogether", "pad_break");
    public static final String BLACKLIST_URL = "mod://webdisplaystogether/blacklisted.html";
    public static final Gson GSON = new Gson();
    public static final ResourceLocation CAPABILITY = new ResourceLocation("webdisplaystogether", "customdatacap");

    //Sounds
    public SoundEvent soundTyping;
    public SoundEvent soundUpgradeAdd;
    public SoundEvent soundUpgradeDel;
    public SoundEvent soundScreenCfg;
    public SoundEvent soundIronic;

    //Criterions
    public Criterion criterionPadBreak;
    public Criterion criterionUpgradeScreen;
    public Criterion criterionLinkPeripheral;
    public Criterion criterionKeyboardCat;

    //Config
    public static final double PAD_RATIO = 59.0 / 30.0;
    public double padResX;
    public double padResY;
    private int lastPadId = 0;
    public double unloadDistance2;
    public double loadDistance2;
    public int miniservPort;
    public long miniservQuota;
    public float ytVolume;
    public float avDist100;
    public float avDist0;
    
    // mod detection
    private boolean hasOC;
    private boolean hasCC;

    public WebDisplays() {
        INSTANCE = this;

        // Give JavaCPP (FFmpeg VP8/Opus codecs) a PER-PROCESS native cache.
        // A shared cache (the default ~/.javacpp, or one per game dir) breaks
        // as soon as any other/stale JVM has the DLLs mapped: extraction fails
        // with "file in use", the codec class initializer is poisoned for the
        // whole session and every screen stays black. A unique directory per
        // process cannot collide; stale directories of dead processes are
        // cleaned up in the background.
        if (System.getProperty("org.bytedeco.javacpp.cachedir") == null) {
            java.nio.file.Path cacheBase = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("wd_javacpp_cache");
            java.nio.file.Path myCache = cacheBase.resolve("pid-" + ProcessHandle.current().pid());
            System.setProperty("org.bytedeco.javacpp.cachedir", myCache.toString());

            Thread housekeeper = new Thread(() -> cleanStaleJavacppCaches(cacheBase, myCache), "WDT-JavaCPP-CacheClean");
            housekeeper.setDaemon(true);
            housekeeper.start();
        }

        // Preload the FFmpeg natives once, off-thread: failures show up in the
        // log immediately (instead of as black screens later), and all later
        // codec instantiations find the libraries already loaded. The natives
        // are first extracted to a plain directory (see FfmpegNatives for why).
        Thread ffmpegPreload = new Thread(() -> {
            net.montoyo.wd.video.FfmpegNatives.setup(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
            preloadFfmpeg();
        }, "WDT-FFmpeg-Preload");
        ffmpegPreload.setDaemon(true);
        ffmpegPreload.start();

        if(FMLEnvironment.dist.isClient()) {
            PROXY = DistSafety.createProxy();
        } else {
            PROXY = new SharedProxy();
        }
    
        if (FMLEnvironment.dist.isClient()) {
            // proxies are annoying, so from now on, I'mma be just registering stuff in here
            FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientProxy::onKeybindRegistry);
            MinecraftForge.EVENT_BUS.addListener(ClientProxy::onDrawSelection);
            MinecraftForge.EVENT_BUS.addListener(KeyboardCamera::updateCamera);
            MinecraftForge.EVENT_BUS.addListener(KeyboardCamera::gameTick);
            ClientConfig.init();
        }
        
        CommonConfig.init();
        
        //Criterions
        criterionPadBreak = new Criterion("pad_break");
        criterionUpgradeScreen = new Criterion("upgrade_screen");
        criterionLinkPeripheral = new Criterion("link_peripheral");
        criterionKeyboardCat = new Criterion("keyboard_cat");
        registerTrigger(criterionPadBreak, criterionUpgradeScreen, criterionLinkPeripheral, criterionKeyboardCat);

        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        
        // Server-side browser engine: bootstrap CEF on dedicated servers.
        // Deliberately deferred until the server has fully started: CEF's native
        // initialization briefly replaces the JVM's signal handlers, and doing
        // that while world generation keeps the JIT busy can kill the process
        // (HotSpot relies on benign SIGSEGVs for implicit null checks).
        if (!FMLEnvironment.dist.isClient())
            MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStartedEvent ev) ->
                    net.montoyo.wd.serverbrowser.ServerCefManager.initDedicated());
        
        WDNetworkRegistry.init();
        SOUNDS.register(bus);
        onRegisterSounds();
        WDTabs.init(bus);
        BlockRegistry.init(bus);
        ItemRegistry.init(bus);
        TileRegistry.init(bus);
        
        PROXY.preInit();
        
        MinecraftForge.EVENT_BUS.register(this);

        //Other things
        PROXY.init();

        PROXY.postInit();
        hasOC = ModList.get().isLoaded("opencomputers");
        hasCC = ModList.get().isLoaded("computercraft");

      /*  if(hasCC) {
            try {
                //We have to do this because the "register" method might be stripped out if CC isn't loaded
                CCPeripheralProvider.class.getMethod("register").invoke(null);
            } catch(Throwable t) {
                Log.error("ComputerCraft was found, but WebDisplaysTogether wasn't able to register its CC Interface Peripheral");
                t.printStackTrace();
            }
        } */
        
        if (!FMLEnvironment.production) {
            ScreenControlRegistry.init();
        }
    }

    @SubscribeEvent
    public static void onAttachPlayerCap(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player && !event.getObject().getCapability(WDDCapability.Provider.cap).isPresent()) {
            event.addCapability(new ResourceLocation("webdisplaystogether", "wddcapability"), new WDDCapability.Provider());
        }
    }

    public void onRegisterSounds() {
        soundTyping = registerSound("keyboard_type");
        soundUpgradeAdd = registerSound("upgrade_add");
        soundUpgradeDel = registerSound("upgrade_del");
        soundScreenCfg = registerSound("screencfg_open");
        soundIronic = registerSound("ironic");
    }

    ArrayList<ResourceKey<Level>> serverStartedDimensions = new ArrayList<>();

    @SubscribeEvent
    public void onWorldLoad(LevelEvent.Load ev) {
        if (ev.getLevel() instanceof Level level) {
            if (ev.getLevel().isClientSide() || level.dimension() != Level.OVERWORLD)
                return;

            File worldDir = Objects.requireNonNull(ev.getLevel().getServer()).getServerDirectory();
            File f = new File(worldDir, "wd_next.txt");

            if (f.exists()) {
                try {
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String idx = br.readLine();
                    Util.silentClose(br);

                    if (idx == null)
                        throw new RuntimeException("Seems like the file is empty (1)");

                    idx = idx.trim();
                    if (idx.isEmpty())
                        throw new RuntimeException("Seems like the file is empty (2)");

                    lastPadId = Integer.parseInt(idx); //This will throw NumberFormatException if it goes wrong
                } catch (Throwable t) {
                    Log.warningEx("Could not read last minePad ID from %s. I'm afraid this might break all minePads.", t, f.getAbsolutePath());
                }
            }

            if (miniservPort != 0) {
                Server sv = Server.getInstance();

                if(!serverStartedDimensions.contains(level.dimension())) {
                    sv.setPort(miniservPort);
                    sv.setDirectory(new File(worldDir, "wd_filehost"));
                    sv.start();
                    serverStartedDimensions.add(level.dimension());
                }
            }
        }
    }

    @SubscribeEvent
    public void onWorldLeave(LevelEvent.Unload ev) throws IOException {
        if(ev.getLevel() instanceof Level level) {
            if (ev.getLevel().isClientSide() || level.dimension() != Level.OVERWORLD)
                return;
            Server sw = Server.getInstance();
            sw.stopServer();
            serverStartedDimensions.remove(level.dimension());
        }
    }

    @SubscribeEvent
    public void onWorldSave(LevelEvent.Save ev) {
        if(ev.getLevel() instanceof Level level) {
            if (ev.getLevel().isClientSide() || level.dimension() != Level.OVERWORLD)
                return;
            File f = new File(Objects.requireNonNull(ev.getLevel().getServer()).getServerDirectory(), "wd_next.txt");

            try {
                BufferedWriter bw = new BufferedWriter(new FileWriter(f));
                bw.write("" + lastPadId + "\n");
                Util.silentClose(bw);
            } catch (Throwable t) {
                Log.warningEx("Could not save last minePad ID (%d) to %s. I'm afraid this might break all minePads.", t, lastPadId, f.getAbsolutePath());
            }
        }
    }

    @SubscribeEvent
    public void onToss(ItemTossEvent ev) {
        if(!ev.getEntity().level().isClientSide) {
            ItemStack is = ev.getEntity().getItem();

            if(is.getItem() == ItemRegistry.MINEPAD.get()) {
                CompoundTag tag = is.getTag();

                if(tag == null) {
                    tag = new CompoundTag();
                    is.setTag(tag);
                }

                UUID thrower = ev.getPlayer().getGameProfile().getId();
                tag.putLong("ThrowerMSB", thrower.getMostSignificantBits());
                tag.putLong("ThrowerLSB", thrower.getLeastSignificantBits());
                tag.putDouble("ThrowHeight", ev.getPlayer().getY() + ev.getPlayer().getEyeHeight());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerCraft(PlayerEvent.ItemCraftedEvent ev) {
        if(CommonConfig.hardRecipes && ItemRegistry.isCompCraftItem(ev.getCrafting().getItem()) && (CraftComponent.EXTCARD.makeItemStack().is(ev.getCrafting().getItem()))) {
            if((ev.getEntity() instanceof ServerPlayer && !hasPlayerAdvancement((ServerPlayer) ev.getEntity(), ADV_PAD_BREAK)) || PROXY.hasClientPlayerAdvancement(ADV_PAD_BREAK) != HasAdvancement.YES) {
                ev.getCrafting().setDamageValue(CraftComponent.BADEXTCARD.ordinal());

                if(!ev.getEntity().level().isClientSide)
                    ev.getEntity().level().playSound(null, ev.getEntity().getX(), ev.getEntity().getY(), ev.getEntity().getZ(), SoundEvents.ITEM_BREAK, SoundSource.MASTER, 1.0f, 1.0f);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStop(ServerStoppingEvent ev) throws IOException {
        net.montoyo.wd.serverbrowser.ServerBrowserManager.shutdownAll();
        net.montoyo.wd.serverbrowser.ServerCefManager.shutdown();
        Server.getInstance().stopServer();
    }

    @SubscribeEvent
    public void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent ev) {
        if (ev.phase == net.minecraftforge.event.TickEvent.Phase.END)
            net.montoyo.wd.serverbrowser.ServerBrowserManager.tick(ev.getServer());
    }

    @SubscribeEvent
    public void onLogIn(PlayerEvent.PlayerLoggedInEvent ev) {
        if(!ev.getEntity().level().isClientSide && ev.getEntity() instanceof ServerPlayer) {
            PacketDistributor.PacketTarget packetDistrutor = PacketDistributor.PLAYER.with(
                    () -> (ServerPlayer) ev.getEntity()
            );

            S2CMessageServerInfo message = new S2CMessageServerInfo(miniservPort);

            WDNetworkRegistry.INSTANCE.send(packetDistrutor, message);

            sendDisclaimer((ServerPlayer) ev.getEntity());

            if (Boolean.getBoolean("wdt.e2eScreen"))
                spawnE2EScreen((ServerPlayer) ev.getEntity());
        }
    }

    /**
     * Legal notice shown once per login. Server-side browsing exposes players
     * and operators to third-party web content; the notice makes explicit that
     * the mod's authors accept no liability and that continued use constitutes
     * acceptance.
     *
     * Delivery is intentionally PRIVATE: sendSystemMessage on a ServerPlayer
     * goes only to that player's own chat (bottom-left), never to the whole
     * server - do NOT change this to PlayerList.broadcastSystemMessage.
     * PlayerLoggedInEvent also fires on the integrated server, so the notice
     * reaches single-player worlds and LAN guests as well.
     */
    private static void sendDisclaimer(ServerPlayer ply) {
        ply.sendSystemMessage(net.minecraft.network.chat.Component.literal("[WebDisplaysTogether] LEGAL NOTICE & DISCLAIMER")
                .withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD));
        ply.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "This server uses WebDisplaysTogether, which renders live third-party web content on the server "
                + "and streams it to players. The mod's authors and contributors exercise no control over, and "
                + "assume no responsibility or liability whatsoever for, any content accessed or displayed, nor "
                + "for any security, privacy, data-protection or other legal risks arising from such use. All "
                + "browsing occurs at the sole discretion and risk of the server operator and its players. Never "
                + "enter personal credentials on shared screens. By remaining on this server with this mod "
                + "active, you acknowledge this notice and accept these terms in full; if you do not accept "
                + "them, discontinue use immediately.")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /**
     * Testing hook (-Dwdt.e2eScreen=true): builds a ready-to-stream 3x2 screen
     * next to the player who just logged in, so the whole server-render ->
     * encode -> network -> client-decode pipeline can be exercised without any
     * manual gameplay.
     */
    private void spawnE2EScreen(ServerPlayer ply) {
        ply.getServer().execute(() -> {
            try {
                net.minecraft.world.level.Level lvl = ply.level();
                // Face NORTH = visible from -z; the wall is south of the player,
                // who is then teleported to look straight at it (yaw 0 = +z).
                net.montoyo.wd.utilities.data.BlockSide side = net.montoyo.wd.utilities.data.BlockSide.NORTH;
                BlockPos ppos = ply.blockPosition();
                BlockPos base = ppos.offset(1, 1, 4);

                // Lay out a 3x2 wall of screen blocks in the side's plane
                for (int x = 0; x < 3; x++) {
                    for (int y = 0; y < 2; y++) {
                        BlockPos p = base.offset(
                                side.right.x * x + side.up.x * y,
                                side.right.y * x + side.up.y * y,
                                side.right.z * x + side.up.z * y);
                        lvl.setBlockAndUpdate(p, net.montoyo.wd.registry.BlockRegistry.SCREEN_BLOCk.get()
                                .defaultBlockState().setValue(net.montoyo.wd.block.ScreenBlock.hasTE, false));
                    }
                }

                // Find the multiblock origin the same way the screwdriver does
                net.montoyo.wd.utilities.math.Vector3i org = new net.montoyo.wd.utilities.math.Vector3i(base.getX(), base.getY(), base.getZ());
                net.montoyo.wd.utilities.Multiblock.findOrigin(lvl, org, side, null);
                net.montoyo.wd.utilities.math.Vector2i size = net.montoyo.wd.utilities.Multiblock.measure(lvl, org, side);

                BlockPos originPos = org.toBlock();
                lvl.setBlockAndUpdate(originPos, lvl.getBlockState(originPos).setValue(net.montoyo.wd.block.ScreenBlock.hasTE, true));
                net.montoyo.wd.entity.ScreenBlockEntity te = (net.montoyo.wd.entity.ScreenBlockEntity) lvl.getBlockEntity(originPos);
                te.addScreen(side, size, null, ply, true);
                te.setScreenURL(side, "https://example.com/");

                // Make sure the player is actually looking at the screen so the
                // client-side renderer opens the stream.
                ply.connection.teleport(ppos.getX() + 0.5, ppos.getY(), ppos.getZ() + 0.5, 0.0f, 0.0f);

                Log.info("E2E: created %dx%d test screen at %s (side %s) for player %s",
                        size.x, size.y, originPos.toShortString(), side, ply.getName().getString());
            } catch (Throwable t) {
                Log.errorEx("E2E: failed to create the test screen", t);
            }
        });
    }

    @SubscribeEvent
    public void onLogOut(PlayerEvent.PlayerLoggedOutEvent ev) {
        if(!ev.getEntity().level().isClientSide)
            Server.getInstance().getClientManager().revokeClientKey(ev.getEntity().getGameProfile().getId());
    }

    @SubscribeEvent
    public void attachEntityCaps(AttachCapabilitiesEvent<Entity> ev) {
        if(ev.getObject() instanceof Player)
            ev.addCapability(CAPABILITY, new WDDCapability.Provider());
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone ev) {
        IWDDCapability src =  ev.getOriginal().getCapability(WDDCapability.Provider.cap, null).orElse(new WDDCapability.Factory().call());
        IWDDCapability dst =  ev.getEntity().getCapability(WDDCapability.Provider.cap, null).orElse(new WDDCapability.Factory().call());

        if(src == null) {
            Log.error("src is null");
            return;
        }

        if(dst == null) {
            Log.error("dst is null");
            return;
        }

        src.cloneTo(dst);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent ev) {
        String msg = ev.getMessage().getString().replaceAll("\\s+", " ").toLowerCase();
        StringBuilder sb = new StringBuilder(msg.length());
        for(int i = 0; i < msg.length(); i++) {
            char chr = msg.charAt(i);

            if(chr != '.' && chr != ',' && chr != ';' && chr != '!' && chr != '?' && chr != ':' && chr != '\'' && chr != '\"' && chr != '`')
                sb.append(chr);
        }

        if(sb.toString().equals("ironic he could save others from death but not himself")) {
            Player ply = ev.getPlayer();
            ply.level().playSound(null, ply.getX(), ply.getY(), ply.getZ(), soundIronic, SoundSource.PLAYERS, 1.0f, 1.0f);
        }
    }

    @SubscribeEvent
    public void onClientChat(ClientChatEvent ev) {
        if(ev.getMessage().equals("!WD render recipes"))
            PROXY.renderRecipes();
    }

    private boolean hasPlayerAdvancement(ServerPlayer ply, ResourceLocation rl) {
        MinecraftServer server = PROXY.getServer();
        if(server == null)
            return false;

        Advancement adv = server.getAdvancements().getAdvancement(rl);
        return adv != null && ply.getAdvancements().getOrStartProgress(adv).isDone();
    }

    public static int getNextAvailablePadID() {
        return new WebDisplays().lastPadId++;
    }

    public static DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, "webdisplaystogether");

    private static SoundEvent registerSound(String resName) {
        ResourceLocation resLoc = new ResourceLocation("webdisplaystogether", resName);
        SoundEvent ret = SoundEvent.createVariableRangeEvent(resLoc);

        SOUNDS.register(resName, () -> ret);
        return ret;
    }

    private static void registerTrigger(Criterion ... criteria) {
        for(Criterion c: criteria)
            CriteriaTriggers.register(c);
    }

   // public static boolean isOpenComputersAvailable() {
   //     return INSTANCE.hasOC;
  //  }

  //  public static boolean isComputerCraftAvailable() {
  //      return INSTANCE.hasCC;
  //  }

    public static boolean isSiteBlacklisted(String url) {
        try {
            URL url2 = new URL(Util.addProtocol(url));
            for (String str : CommonConfig.Browser.blacklist)
                if (str.equalsIgnoreCase(url2.getHost())) return true;
            return false;
        } catch(MalformedURLException ex) {
            return false;
        }
    }

    public static String applyBlacklist(String url) {
        return isSiteBlacklisted(url) ? BLACKLIST_URL : url;
    }

    // ------------------------------------------------------------------
    // FFmpeg (VP8/Opus) native library management
    // ------------------------------------------------------------------

    /**
     * Extracts + loads the FFmpeg natives with retries; called once at startup.
     *
     * Loading goes through the *presets* classes on purpose: they carry the
     * native-library metadata but have no static native initializers, so a
     * failed attempt (e.g. the antivirus briefly locking a freshly extracted
     * DLL) throws cleanly and CAN be retried. Touching the global classes
     * (org.bytedeco.ffmpeg.global.*) first would poison their class
     * initializers forever on the first failure. Once the presets have loaded
     * every shared library, the global classes initialize trivially.
     */
    /** 0 = still loading, 1 = ready, 2 = failed for good. */
    private static volatile int ffmpegState = 0;

    /** True once the FFmpeg natives are loaded and the codecs can be used. */
    public static boolean isFfmpegReady() {
        return ffmpegState == 1;
    }

    /** True if FFmpeg loading failed permanently (codecs will never work this session). */
    public static boolean hasFfmpegFailed() {
        return ffmpegState == 2;
    }

    private static void preloadFfmpeg() {
        final int maxAttempts = 5;
        for (int attempt = 1; ; attempt++) {
            try {
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.presets.avutil.class);
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.presets.swresample.class);
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.presets.avcodec.class);
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.presets.swscale.class);

                // Safe now: all shared libraries are in memory.
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avutil.class);
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avcodec.class);
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.swscale.class);

                ffmpegState = 1;
                Log.info("FFmpeg natives loaded; the VP8/Opus streaming codecs are ready.");
                return;
            } catch (Throwable t) {
                if (attempt >= maxAttempts) {
                    ffmpegState = 2;
                    Log.errorEx("Failed to load the FFmpeg natives after " + maxAttempts + " attempts. Video streaming "
                            + "will NOT work (screens stay black for remote players).", t);
                    return;
                }
                Log.warningEx("FFmpeg natives failed to load (attempt " + attempt + "/" + maxAttempts
                        + "); retrying in a moment... (" + t + ")", t);
                try {
                    Thread.sleep(1500L * attempt);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Deletes javacpp cache directories left behind by dead processes (best effort). */
    private static void cleanStaleJavacppCaches(java.nio.file.Path base, java.nio.file.Path keep) {
        try {
            if (!java.nio.file.Files.isDirectory(base))
                return;

            try (java.util.stream.Stream<java.nio.file.Path> dirs = java.nio.file.Files.list(base)) {
                dirs.filter(p -> !p.equals(keep)).forEach(p -> {
                    String name = p.getFileName().toString();
                    if (name.startsWith("pid-")) {
                        try {
                            if (ProcessHandle.of(Long.parseLong(name.substring(4))).isPresent())
                                return; // that process is still alive; leave its cache alone
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    // stale pid dir, or leftovers from the old shared-cache layout
                    deleteRecursively(p);
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursively(java.nio.file.Path path) {
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.delete(p);
                } catch (java.io.IOException ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }
}

