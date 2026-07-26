package net.montoyo.wd.client.audio;

import net.minecraft.core.BlockPos;
import net.montoyo.wd.entity.SpeakerBlockEntity;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector3i;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-side registry of loaded speaker block entities, so the audio system
 * can find the speakers linked to a given screen. All access from the client
 * main thread.
 */
public final class ClientSpeakerRegistry {
    private static final Set<SpeakerBlockEntity> speakers = new LinkedHashSet<>();

    private ClientSpeakerRegistry() {
    }

    public static void register(SpeakerBlockEntity speaker) {
        speakers.add(speaker);
    }

    public static void unregister(SpeakerBlockEntity speaker) {
        speakers.remove(speaker);
    }

    /** All loaded speakers linked to the given screen. */
    public static List<SpeakerBlockEntity> forScreen(BlockPos pos, BlockSide side) {
        List<SpeakerBlockEntity> out = new ArrayList<>(2);

        for (SpeakerBlockEntity speaker : speakers) {
            if (speaker.isRemoved())
                continue;

            Vector3i sp = speaker.getScreenPos();
            if (sp != null && speaker.getScreenSide() == side
                    && sp.x == pos.getX() && sp.y == pos.getY() && sp.z == pos.getZ())
                out.add(speaker);
        }

        return out;
    }

    public static void clear() {
        speakers.clear();
    }
}
