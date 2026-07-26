/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.utilities.math;

import net.minecraft.world.phys.AABB;

public final class MutableAABB extends AABB {
    public MutableAABB() {
        super(0, 0, 0, 0, 0, 0);
    }

    public MutableAABB(Vector3i pos) {
        super(pos.x, pos.y, pos.z, pos.x, pos.y, pos.z);
    }

    public MutableAABB(Vector3i a, Vector3i b) {
        super(a.x, a.y, a.z, b.x, b.y, b.z);
    }

    public MutableAABB(net.minecraft.world.phys.AABB bb) {
        super(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    }

    public MutableAABB(double x1, double y1, double z1, double x2, double y2, double z2) {
        super(x1, y1, z1, x2, y2, z2);
    }

    public MutableAABB expand(Vector3i vec) {
        if (vec.x > maxX)
            maxX = vec.x;
        else if (vec.x < minX)
            minX = vec.x;

        if (vec.y > maxY)
            maxY = vec.y;
        else if (vec.y < minY)
            minY = vec.y;

        if (vec.z > maxZ)
            maxZ = vec.z;
        else if (vec.z < minZ)
            minZ = vec.z;

        return this;
    }

    @Override
    public AABB move(double x, double y, double z) {
        minX += x;
        minY += y;
        minZ += z;
        maxX += x;
        maxY += y;
        maxZ += z;
        return this;
    }

    public net.minecraft.world.phys.AABB toMc() {
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void setAndCheck(double x1, double y1, double z1, double x2, double y2, double z2) {
        minX = Math.min(x1, x2);
        minY = Math.min(y1, y2);
        minZ = Math.min(z1, z2);

        maxX = Math.max(x1, x2);
        maxY = Math.max(y1, y2);
        maxZ = Math.max(z1, z2);
    }

    public void expand(double x1, double y1, double z1, double x2, double y2, double z2) {
        minX = Math.min(minX, Math.min(x1, x2));
        minY = Math.min(minY, Math.min(y1, y2));
        minZ = Math.min(minZ, Math.min(z1, z2));

        maxX = Math.max(maxX, Math.max(x1, x2));
        maxY = Math.max(maxY, Math.max(y1, y2));
        maxZ = Math.max(maxZ, Math.max(z1, z2));
    }
}
