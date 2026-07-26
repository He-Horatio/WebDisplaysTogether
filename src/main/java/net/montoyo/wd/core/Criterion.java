/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.core;

import com.google.gson.JsonObject;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class Criterion implements CriterionTrigger<Criterion.Instance> {
    public static class Instance extends AbstractCriterionTriggerInstance {
        public Instance(ResourceLocation id, ContextAwarePredicate arg2) {
            super(id, arg2);
        }
    }

    private final ResourceLocation id;
    private final HashMap<PlayerAdvancements, ArrayList<Listener<Instance>>> map = new HashMap<>();

    public Criterion(@Nonnull String name) {
        id = new ResourceLocation("webdisplaystogether", name);
    }

    @Override
    @Nonnull
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public void addPlayerListener(PlayerAdvancements adv, Listener<Instance> l) {
        map.computeIfAbsent(adv, k -> new ArrayList<>()).add(l);
    }

    @Override
    public void removePlayerListener(PlayerAdvancements adv, Listener<Instance> l) {
        map.computeIfPresent(adv, (k, v) -> {
            v.remove(l);
            return v.isEmpty() ? null : v;
        });
    }

    @Override
    public void removePlayerListeners(PlayerAdvancements adv) {
        map.remove(adv);
    }

    @Override
    public @NotNull Instance createInstance(JsonObject json, DeserializationContext context) {
        return new Instance(id, EntityPredicate.fromJson(json, "instance", context));
    }

    public void trigger(PlayerAdvancements ply) {
        ArrayList<Listener<Instance>> listeners = map.get(ply);

        if (listeners != null) {
            Listener[] copy = listeners.toArray(new Listener[0]); //We need to make a copy, otherwise we get a ConcurrentModificationException
            Arrays.stream(copy).forEach(l -> l.run(ply));
        }
    }
}
