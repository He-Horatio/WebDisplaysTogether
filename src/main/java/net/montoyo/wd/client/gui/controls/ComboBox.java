/*
 * Copyright (C) 2026
 */

package net.montoyo.wd.client.gui.controls;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.montoyo.wd.client.gui.loading.JsonOWrapper;

import java.util.ArrayList;

/**
 * A simple drop-down combo box. Collapsed it shows the current selection;
 * clicking it expands an option list drawn on top of everything else (postDraw).
 * Fires {@link SelectionChangedEvent} when the user picks another option.
 */
public class ComboBox extends BasicControl {

    public static class SelectionChangedEvent extends Event<ComboBox> {

        private final int index;

        public SelectionChangedEvent(ComboBox cb) {
            source = cb;
            index = cb.selected;
        }

        public int getIndex() {
            return index;
        }

    }

    public static final int ITEM_HEIGHT = 12;

    private final ArrayList<String> options = new ArrayList<>();
    private int selected = 0;
    private int width = 100;
    private int height = 14;
    private boolean expanded = false;
    private int hoverIdx = -1;

    public ComboBox() {
    }

    public ComboBox(int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.width = width;
    }

    public void setOptions(String... opts) {
        options.clear();
        for(String o : opts)
            options.add(o);

        if(selected >= options.size())
            selected = 0;
    }

    public int getSelectedIndex() {
        return selected;
    }

    public void setSelectedIndex(int idx) {
        if(idx >= 0 && idx < options.size())
            selected = idx;
    }

    public String getSelectedOption() {
        return (selected >= 0 && selected < options.size()) ? options.get(selected) : "";
    }

    public boolean isExpanded() {
        return expanded;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if(mouseButton != 0 || disabled || !visible)
            return false;

        if(expanded) {
            int listY = y + height;

            if(mouseX >= x && mouseX < x + width && mouseY >= listY && mouseY < listY + options.size() * ITEM_HEIGHT) {
                int idx = (int) ((mouseY - listY) / ITEM_HEIGHT);
                expanded = false;

                if(idx != selected) {
                    selected = idx;
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    parent.actionPerformed(new SelectionChangedEvent(this));
                }

                return true;
            }

            expanded = false;
            //Consume the click if it was on the box itself, otherwise let it through
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }

        if(mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height) {
            expanded = true;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseMove(double mouseX, double mouseY) {
        hoverIdx = -1;

        if(expanded) {
            int listY = y + height;

            if(mouseX >= x && mouseX < x + width && mouseY >= listY && mouseY < listY + options.size() * ITEM_HEIGHT)
                hoverIdx = (int) ((mouseY - listY) / ITEM_HEIGHT);
        }

        return false;
    }

    @Override
    public void unfocus() {
        expanded = false;
    }

    @Override
    public void draw(GuiGraphics poseStack, int mouseX, int mouseY, float ptt) {
        if(!visible)
            return;

        //Collapsed box
        poseStack.fill(x, y, x + width, y + height, disabled ? 0xFF303030 : 0xFF000000);
        drawBorder(poseStack, x, y, width, height, disabled ? 0xFF808080 : COLOR_WHITE);

        String txt = getSelectedOption();
        poseStack.drawString(font, txt, x + 4, y + (height - 8) / 2 + 1, disabled ? 0xFF808080 : COLOR_WHITE, false);

        //Arrow
        String arrow = expanded ? "\u25B2" : "\u25BC";
        poseStack.drawString(font, arrow, x + width - 10, y + (height - 8) / 2 + 1, disabled ? 0xFF808080 : COLOR_WHITE, false);
    }

    @Override
    public void postDraw(GuiGraphics poseStack, int mouseX, int mouseY, float ptt) {
        if(!visible || !expanded)
            return;

        poseStack.pose().pushPose();
        poseStack.pose().translate(0.0f, 0.0f, 400.0f); //Draw on top of everything

        int listY = y + height;
        int listH = options.size() * ITEM_HEIGHT;

        poseStack.fill(x, listY, x + width, listY + listH, 0xFF101010);
        drawBorder(poseStack, x, listY, width, listH, COLOR_WHITE);

        for(int i = 0; i < options.size(); i++) {
            int oy = listY + i * ITEM_HEIGHT;

            if(i == hoverIdx)
                poseStack.fill(x + 1, oy, x + width - 1, oy + ITEM_HEIGHT, 0xFF3060A0);

            int color = (i == selected) ? 0xFFFFFF00 : COLOR_WHITE;
            poseStack.drawString(font, options.get(i), x + 4, oy + 2, color, false);
        }

        poseStack.pose().popPose();
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    public void setSize(int w, int h) {
        width = w;
        height = h;
    }

    @Override
    public void load(JsonOWrapper json) {
        super.load(json);
        width = json.getInt("width", 100);
        height = json.getInt("height", 14);
        selected = json.getInt("selected", 0);

        options.clear();
        JsonElement optsElem = json.getObject().get("options");
        if(optsElem != null && optsElem.isJsonArray()) {
            JsonArray arr = optsElem.getAsJsonArray();

            for(JsonElement e : arr)
                options.add(tr(e.getAsString()));
        }

        if(selected >= options.size())
            selected = 0;

        parent.requirePostDraw(this);
    }

}
