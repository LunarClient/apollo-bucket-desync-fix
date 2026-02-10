package com.lunarclient.desync;

import com.github.retrooper.packetevents.PacketEvents;
import net.minecraft.server.v1_8_R3.Blocks;
import net.minecraft.server.v1_8_R3.Item;
import net.minecraft.server.v1_8_R3.MinecraftKey;
import org.bukkit.plugin.java.JavaPlugin;

public class DesyncPlugin extends JavaPlugin {

    private static DesyncPlugin instance;

    private BucketPlaceManager bucketPlaceManager;

    @Override
    public void onEnable() {
        instance = this;

        this.replaceBucket();

        PacketEvents.getAPI().init();
        PacketEvents.getAPI().getEventManager().registerListener(this.bucketPlaceManager = new BucketPlaceManager());
    }

    private void replaceBucket() {
        Item customBucket = new ApolloItemBucket(Blocks.AIR)
            .c("bucket").c(16);

        Item.REGISTRY.a(325, new MinecraftKey("bucket"), customBucket);
        Item.REGISTRY.a(326, new MinecraftKey("water_bucket"), new ApolloItemBucket(Blocks.FLOWING_WATER)
            .c("bucketWater").c(customBucket));
        Item.REGISTRY.a(327, new MinecraftKey("lava_bucket"), new ApolloItemBucket(Blocks.FLOWING_LAVA)
            .c("bucketLava").c(customBucket));
    }

    public static DesyncPlugin getInstance() {
        return instance;
    }

    public BucketPlaceManager getBucketPlaceManager() {
        return this.bucketPlaceManager;
    }

}