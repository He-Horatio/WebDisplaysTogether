/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.core;

/**
 * Screen capabilities. Since 3.0, screens have these built in and there are
 * no upgrade items anymore.
 */
public enum DefaultUpgrade {
    LASERMOUSE("lasermouse", "LaserMouse"),
    GPS("gps", "GPS");

    public final String name;
    public final String wikiName;

    DefaultUpgrade(String n, String wn) {
        name = n;
        wikiName = wn;
    }

    @Override
    public String toString() {
        return name;
    }
}
