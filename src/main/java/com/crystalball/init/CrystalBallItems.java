package com.crystalball.init;

import com.crystalball.CrystalBallMod;
import com.crystalball.item.CrystalBallItem;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Item registration of the Crystal Ball. */
public class CrystalBallItems {

    public static final DeferredRegister<Item> REGISTRY = DeferredRegister.create(ForgeRegistries.ITEMS, CrystalBallMod.MODID);
    public static final RegistryObject<Item> CRYSTAL_BALL = REGISTRY.register("crystal_ball", CrystalBallItem::new);
}