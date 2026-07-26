/*
 * Copyright (C) 2026
 */

package net.montoyo.wd.client.gui.controls;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.montoyo.wd.client.gui.loading.JsonOWrapper;

/**
 * A horizontal slider control wrapping the vanilla {@link AbstractSliderButton}.
 * Fires {@link ValueChangedEvent} whenever the value changes (while dragging).
 */
public class Slider extends Control {

    public static class ValueChangedEvent extends Event<Slider> {

        private final int value;

        public ValueChangedEvent(Slider slider) {
            source = slider;
            value = slider.getValue();
        }

        public int getValue() {
            return value;
        }

    }

    private class McSlider extends AbstractSliderButton {

        McSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), 0.0);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.nullToEmpty(label + ": " + getValue() + suffix));
        }

        @Override
        protected void applyValue() {
            parent.actionPerformed(new ValueChangedEvent(Slider.this));
        }

        void setRatio(double ratio) {
            value = Math.max(0.0, Math.min(1.0, ratio));
            updateMessage();
        }

        double getRatio() {
            return value;
        }

    }

    private final McSlider slider;
    private boolean dragging = false;
    private int min = 0;
    private int max = 100;
    private String label = "";
    private String suffix = "";

    public Slider() {
        slider = new McSlider(0, 0, 100, 20);
    }

    public Slider(int x, int y, int width, int min, int max, String label) {
        slider = new McSlider(x, y, width, 20);
        this.min = min;
        this.max = max;
        this.label = label;
        setValue(min);
    }

    public int getValue() {
        return min + (int) Math.round(slider.getRatio() * (double) (max - min));
    }

    public void setValue(int value) {
        value = Math.max(min, Math.min(max, value));
        slider.setRatio(((double) (value - min)) / ((double) (max - min)));
    }

    public void setLabel(String label) {
        this.label = label;
        slider.setRatio(slider.getRatio()); //Refresh message
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if(slider.active && slider.visible && slider.mouseClicked(mouseX, mouseY, mouseButton)) {
            dragging = true;
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseClickMove(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return dragging && slider.active && slider.visible && slider.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int state) {
        if(dragging) {
            dragging = false;
            return slider.mouseReleased(mouseX, mouseY, state);
        }

        return false;
    }

    @Override
    public void draw(GuiGraphics poseStack, int mouseX, int mouseY, float ptt) {
        slider.render(poseStack, mouseX, mouseY, ptt);
    }

    public void setDisabled(boolean dis) {
        slider.active = !dis;
    }

    public boolean isDisabled() {
        return !slider.active;
    }

    public void setVisible(boolean visible) {
        slider.visible = visible;
    }

    public boolean isVisible() {
        return slider.visible;
    }

    @Override
    public int getX() {
        return slider.getX();
    }

    @Override
    public int getY() {
        return slider.getY();
    }

    @Override
    public int getWidth() {
        return slider.getWidth();
    }

    @Override
    public int getHeight() {
        return slider.getHeight();
    }

    @Override
    public void setPos(int x, int y) {
        slider.setPosition(x, y);
    }

    @Override
    public void load(JsonOWrapper json) {
        super.load(json);
        slider.setPosition(json.getInt("x", 0), json.getInt("y", 0));
        slider.setWidth(json.getInt("width", 100));
        slider.setHeight(json.getInt("height", 20));
        min = json.getInt("min", 0);
        max = json.getInt("max", 100);
        label = tr(json.getString("label", ""));
        suffix = tr(json.getString("suffix", ""));
        slider.visible = json.getBool("visible", true);
        slider.active = !json.getBool("disabled", false);
        setValue(json.getInt("value", min));
    }

}
