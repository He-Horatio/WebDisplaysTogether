package net.montoyo.wd.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.montoyo.wd.WebDisplays;
import net.montoyo.wd.client.ClientProxy;
import net.montoyo.wd.client.stream.ClientStreamManager;
import net.montoyo.wd.config.CommonConfig;
import net.montoyo.wd.core.BrowseMode;
import net.montoyo.wd.core.IScreenStream;
import net.montoyo.wd.core.ScreenRights;
import net.montoyo.wd.core.ScreenSoundMode;
import net.montoyo.wd.utilities.*;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import net.montoyo.wd.utilities.math.Vector2i;
import net.montoyo.wd.utilities.serialization.NameUUIDPair;

import java.util.ArrayList;
import java.util.UUID;

public class ScreenData {
    public BlockSide side;
    public Vector2i size;
    public Vector2i resolution;
    public Rotation rotation = Rotation.ROT_0;
    public String url;
    public VideoType videoType;
    public NameUUIDPair owner;
    public ArrayList<NameUUIDPair> friends;
    public int friendRights;
    public int otherRights;
    /** Client-side only: video stream of the server-rendered browser. */
    public IScreenStream stream;
    public ArrayList<ItemStack> upgrades;
    public boolean doTurnOnAnim;
    public long turnOnTime;
    public Player laserUser;
    public final Vector2i lastMousePos = new Vector2i();
    public NibbleArray redstoneStatus; //null on client
    public boolean autoVolume = false;
    public BrowseMode browseMode = BrowseMode.SERVER;
    public int brightness = 100; //0-300 percent
    public int volume = 100; //0-300 percent
    public ScreenSoundMode soundMode = ScreenSoundMode.SCREEN;

    public int mouseType;

    public static ScreenData deserialize(CompoundTag tag) {
        ScreenData ret = new ScreenData();
        ret.side = BlockSide.values()[tag.getByte("Side")];
        ret.size = new Vector2i(tag.getInt("Width"), tag.getInt("Height"));
        ret.resolution = new Vector2i(tag.getInt("ResolutionX"), tag.getInt("ResolutionY"));
        ret.rotation = Rotation.values()[tag.getByte("Rotation")];
        ret.url = tag.getString("URL");
        ret.videoType = VideoType.getTypeFromURL(ret.url);

        if (ret.resolution.x <= 0 || ret.resolution.y <= 0) {
            float psx = ((float) ret.size.x) * 16.f - 4.f;
            float psy = ((float) ret.size.y) * 16.f - 4.f;
            psx *= 8.f; //TODO: Use ratio in config file
            psy *= 8.f;

            ret.resolution.x = (int) psx;
            ret.resolution.y = (int) psy;
        }

        if (tag.contains("OwnerName")) {
            String name = tag.getString("OwnerName");
            UUID uuid = tag.getUUID("OwnerUUID");
            ret.owner = new NameUUIDPair(name, uuid);
        }

        ListTag friends = tag.getList("Friends", 10);
        ret.friends = new ArrayList<>(friends.size());

        for (int i = 0; i < friends.size(); i++) {
            CompoundTag nf = friends.getCompound(i);
            NameUUIDPair pair = new NameUUIDPair(nf.getString("Name"), nf.getUUID("UUID"));
            ret.friends.add(pair);
        }

        ret.friendRights = tag.getByte("FriendRights");
        ret.otherRights = tag.getByte("OtherRights");

        ListTag upgrades = tag.getList("Upgrades", 10);
        ret.upgrades = new ArrayList<>();

        for (int i = 0; i < upgrades.size(); i++)
            ret.upgrades.add(ItemStack.of(upgrades.getCompound(i)));

        if (tag.contains("AutoVolume"))
            ret.autoVolume = tag.getBoolean("AutoVolume");

        if (tag.contains("BrowseMode"))
            ret.browseMode = BrowseMode.of(tag.getByte("BrowseMode"));

        if (tag.contains("Brightness"))
            ret.brightness = tag.getInt("Brightness");

        if (tag.contains("Volume"))
            ret.volume = tag.getInt("Volume");

        if (tag.contains("SoundMode"))
            ret.soundMode = ScreenSoundMode.of(tag.getByte("SoundMode"));

        return ret;
    }

    public CompoundTag serialize() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("Side", (byte) side.ordinal());
        tag.putInt("Width", size.x);
        tag.putInt("Height", size.y);
        tag.putInt("ResolutionX", resolution.x);
        tag.putInt("ResolutionY", resolution.y);
        tag.putByte("Rotation", (byte) rotation.ordinal());
        tag.putString("URL", url);

        if (owner == null)
            Log.warning("Found TES with NO OWNER!!");
        else {
            tag.putString("OwnerName", owner.name);
            tag.putUUID("OwnerUUID", owner.uuid);
        }

        ListTag list = new ListTag();
        for (NameUUIDPair f : friends) {
            CompoundTag nf = new CompoundTag();
            nf.putString("Name", f.name);
            nf.putUUID("UUID", f.uuid);

            list.add(nf);
        }

        tag.put("Friends", list);
        tag.putByte("FriendRights", (byte) friendRights);
        tag.putByte("OtherRights", (byte) otherRights);

        list = new ListTag();
        for (ItemStack is : upgrades)
            list.add(is.save(new CompoundTag()));

        tag.put("Upgrades", list);
        tag.putBoolean("AutoVolume", autoVolume);
        tag.putByte("BrowseMode", (byte) browseMode.ordinal());
        tag.putInt("Brightness", brightness);
        tag.putInt("Volume", volume);
        tag.putByte("SoundMode", (byte) soundMode.ordinal());
        return tag;
    }

    public int rightsFor(Player ply) {
        return rightsFor(ply.getGameProfile().getId());
    }

    public int rightsFor(UUID uuid) {
        if (owner.uuid.equals(uuid))
            return ScreenRights.ALL;

        return friends.stream().anyMatch(f -> f.uuid.equals(uuid)) ? friendRights : otherRights;
    }

    public void setupRedstoneStatus(Level world, BlockPos start) {
        if (world.isClientSide()) {
            Log.warning("Called Screen.setupRedstoneStatus() on client.");
            return;
        }

        if (redstoneStatus != null) {
            Log.warning("Called Screen.setupRedstoneStatus() on server, but redstone status is non-null");
            return;
        }

        Direction[] VALUES = Direction.values();
        redstoneStatus = new NibbleArray(size.x * size.y);
        final Direction facing = VALUES[side.reverse().ordinal()];
        final ScreenIterator it = new ScreenIterator(start, side, size);

        while (it.hasNext()) {
            int idx = it.getIndex();
            redstoneStatus.set(idx, world.getSignal(it.next(), facing));
        }
    }


    public void clampResolution() {
        if (resolution.x > CommonConfig.Screen.maxResolutionX) {
            float newY = ((float) resolution.y) * ((float) CommonConfig.Screen.maxResolutionX) / ((float) resolution.x);
            resolution.x = CommonConfig.Screen.maxResolutionX;
            resolution.y = (int) newY;
        }

        if (resolution.y > CommonConfig.Screen.maxResolutionY) {
            float newX = ((float) resolution.x) * ((float) CommonConfig.Screen.maxResolutionY) / ((float) resolution.y);
            resolution.x = (int) newX;
            resolution.y = CommonConfig.Screen.maxResolutionY;
        }
    }

    /**
     * Client-side: opens the display path of this screen. In SERVER mode this
     * subscribes to the server-rendered video stream; in LOCAL mode this spawns
     * a client-side browser like the original WebDisplays.
     */
    public void openStream(ScreenBlockEntity be, boolean doAnim) {
        if (WebDisplays.PROXY instanceof ClientProxy && stream == null && be.getLevel() != null) {
            if (browseMode == BrowseMode.LOCAL)
                stream = new net.montoyo.wd.client.stream.LocalScreenStream(be, this);
            else
                stream = ClientStreamManager.open(be.getLevel(), be.getBlockPos(), side);

            doTurnOnAnim = doAnim;
            turnOnTime = System.currentTimeMillis();
        }
    }

    /**
     * Client-side: unsubscribes from the video stream and frees resources.
     * Safe to call on the server (stream is always null there).
     */
    public void closeStream() {
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }
}
