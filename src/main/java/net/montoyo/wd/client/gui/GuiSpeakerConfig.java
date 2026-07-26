package net.montoyo.wd.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.server_bound.C2SMessageSpeakerPos;

/**
 * "Sound modeling map" for a speaker block: a top-down view of the viewing
 * area where the speaker's relative position can be freely dragged. The
 * horizontal position drives the left/right channel weights; the screen is at
 * the top edge of the map.
 */
public class GuiSpeakerConfig extends Screen {
    private static final int MAP_W = 220;
    private static final int MAP_H = 150;
    private static final int DOT_RADIUS = 4;

    private final BlockPos speakerPos;
    private float relX, relY;
    private boolean dragging = false;
    private boolean dirty = false;

    private int mapX, mapY;

    public GuiSpeakerConfig(BlockPos speakerPos, float relX, float relY) {
        super(Component.translatable("webdisplaystogether.gui.speaker.title"));
        this.speakerPos = speakerPos;
        this.relX = relX;
        this.relY = relY;
    }

    @Override
    protected void init() {
        mapX = (width - MAP_W) / 2;
        mapY = (height - MAP_H) / 2 - 4;

        addRenderableWidget(Button.builder(Component.translatable("webdisplaystogether.gui.speaker.reset"), btn -> {
            relX = 0.0f;
            relY = 0.0f;
            dirty = true;
        }).bounds(width / 2 - 102, mapY + MAP_H + 8, 100, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), btn -> onClose())
                .bounds(width / 2 + 2, mapY + MAP_H + 8, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);

        gfx.drawCenteredString(font, title, width / 2, mapY - 24, 0xFFFFFF);
        gfx.drawCenteredString(font, Component.translatable("webdisplaystogether.gui.speaker.hint"), width / 2, mapY - 12, 0xA0A0A0);

        // map background + border
        gfx.fill(mapX - 1, mapY - 1, mapX + MAP_W + 1, mapY + MAP_H + 1, 0xFF808080);
        gfx.fill(mapX, mapY, mapX + MAP_W, mapY + MAP_H, 0xC0101010);

        // the screen (viewing area) sits along the top edge of the map
        gfx.fill(mapX + 20, mapY + 4, mapX + MAP_W - 20, mapY + 10, 0xFF4A90D9);
        gfx.drawCenteredString(font, "\u25A0 Screen", width / 2, mapY + 14, 0x4A90D9);

        // center cross
        int cx = mapX + MAP_W / 2;
        int cy = mapY + MAP_H / 2;
        gfx.fill(cx - 5, cy, cx + 5, cy + 1, 0x60FFFFFF);
        gfx.fill(cx, cy - 5, cx + 1, cy + 5, 0x60FFFFFF);

        // speaker dot
        int dotX = dotX();
        int dotY = dotY();
        int color = dragging ? 0xFFFFD24A : 0xFFE0E0E0;
        gfx.fill(dotX - DOT_RADIUS, dotY - DOT_RADIUS, dotX + DOT_RADIUS, dotY + DOT_RADIUS, color);
        gfx.drawCenteredString(font, "\u266B", dotX, dotY - 4, 0xFF202020);

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private int dotX() {
        return mapX + (int) ((relX + 1.0f) / 2.0f * (MAP_W - 20)) + 10;
    }

    private int dotY() {
        return mapY + (int) ((relY + 1.0f) / 2.0f * (MAP_H - 40)) + 20;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= mapX && mouseX < mapX + MAP_W && mouseY >= mapY && mouseY < mapY + MAP_H) {
            dragging = true;
            updateFromMouse(mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            updateFromMouse(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            sendPos();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateFromMouse(double mouseX, double mouseY) {
        relX = clamp((float) ((mouseX - mapX - 10) / (MAP_W - 20) * 2.0 - 1.0));
        relY = clamp((float) ((mouseY - mapY - 20) / (MAP_H - 40) * 2.0 - 1.0));
        dirty = true;
    }

    private static float clamp(float v) {
        return Math.max(-1.0f, Math.min(1.0f, v));
    }

    private void sendPos() {
        if (dirty) {
            dirty = false;
            WDNetworkRegistry.INSTANCE.sendToServer(new C2SMessageSpeakerPos(speakerPos, relX, relY));
        }
    }

    @Override
    public void onClose() {
        sendPos();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
