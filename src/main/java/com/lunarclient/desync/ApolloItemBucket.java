package com.lunarclient.desync;

import com.lunarclient.apollo.module.packetenrichment.raytrace.BlockHitResult;
import com.lunarclient.apollo.module.packetenrichment.raytrace.RayTraceResult;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.craftbukkit.v1_8_R3.event.CraftEventFactory;
import org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

public class ApolloItemBucket extends ItemBucket {

    private final Block block;

    public ApolloItemBucket(Block block) {
        super(block);

        this.block = block;
    }

    public ItemStack a(ItemStack item, World world, EntityHuman entity) {
        boolean flag = this.block == Blocks.AIR;

        // Desync fix start
        MovingObjectPosition movingobjectposition = null;

        // Try to get the client's ray trace result first
        BucketPlaceManager bucketManager = DesyncPlugin.getInstance().getBucketPlaceManager();
        if (bucketManager != null && entity instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) entity;
            RayTraceResult clientRayTrace = bucketManager.getAndRemove(player.getUniqueID());

            if (clientRayTrace instanceof BlockHitResult) {
                BlockHitResult blockHit = (BlockHitResult) clientRayTrace;
                movingobjectposition = bucketManager.convertToMovingObjectPosition(blockHit);
            }
        }
        
        // Fallback to server-side ray trace if no client data available
        if (movingobjectposition == null) {
            movingobjectposition = this.a(world, entity, flag);
        }
        // Desync fix end
        
        if (movingobjectposition == null) {
            return item;
        }

        if (movingobjectposition.type != MovingObjectPosition.EnumMovingObjectType.BLOCK) {
            return item;
        }

        BlockPosition blockposition = movingobjectposition.a();
        if (!world.a(entity, blockposition)) {
            return item;
        }

        if (flag) {
            if (!entity.a(blockposition.shift(movingobjectposition.direction), movingobjectposition.direction, item)) {
                return item;
            }

            IBlockData iblockdata = world.getType(blockposition);
            Material material = iblockdata.getBlock().getMaterial();
            if (material == Material.WATER && iblockdata.get(BlockFluids.LEVEL) == 0) {
                PlayerBucketFillEvent event = CraftEventFactory.callPlayerBucketFillEvent(entity,
                    blockposition.getX(), blockposition.getY(), blockposition.getZ(), null, item, Items.WATER_BUCKET);
                if (event.isCancelled()) {
                    return item;
                }

                world.setAir(blockposition);
                entity.b(StatisticList.USE_ITEM_COUNT[Item.getId(this)]);
                return this.a(item, entity, Items.WATER_BUCKET, event.getItemStack());
            }

            if (material == Material.LAVA && iblockdata.get(BlockFluids.LEVEL) == 0) {
                PlayerBucketFillEvent event = CraftEventFactory.callPlayerBucketFillEvent(entity,
                    blockposition.getX(), blockposition.getY(), blockposition.getZ(), null, item, Items.LAVA_BUCKET);
                if (event.isCancelled()) {
                    return item;
                }

                world.setAir(blockposition);
                entity.b(StatisticList.USE_ITEM_COUNT[Item.getId(this)]);
                return this.a(item, entity, Items.LAVA_BUCKET, event.getItemStack());
            }

            return item;
        }

        if (this.block == Blocks.AIR) {
            PlayerBucketEmptyEvent event = CraftEventFactory.callPlayerBucketEmptyEvent(entity,
                blockposition.getX(), blockposition.getY(), blockposition.getZ(), movingobjectposition.direction, item);
            if (event.isCancelled()) {
                return item;
            }

            return CraftItemStack.asNMSCopy(event.getItemStack());
        }

        BlockPosition blockposition1 = blockposition.shift(movingobjectposition.direction);
        if (!entity.a(blockposition1, movingobjectposition.direction, item)) {
            return item;
        }

        PlayerBucketEmptyEvent event = CraftEventFactory.callPlayerBucketEmptyEvent(entity,
            blockposition.getX(), blockposition.getY(), blockposition.getZ(), movingobjectposition.direction, item);
        if (event.isCancelled()) {
            return item;
        }

        if (this.a(world, blockposition1) && !entity.abilities.canInstantlyBuild) {
            entity.b(StatisticList.USE_ITEM_COUNT[Item.getId(this)]);
            return CraftItemStack.asNMSCopy(event.getItemStack());
        }
        
        return item;
    }

    private ItemStack a(ItemStack itemstack, EntityHuman entityhuman, Item item, org.bukkit.inventory.ItemStack result) {
        if (entityhuman.abilities.canInstantlyBuild) {
            return itemstack;
        } else if (--itemstack.count <= 0) {
            return CraftItemStack.asNMSCopy(result);
        } else {
            if (!entityhuman.inventory.pickup(CraftItemStack.asNMSCopy(result))) {
                entityhuman.drop(CraftItemStack.asNMSCopy(result), false);
            }

            return itemstack;
        }
    }

    public boolean a(World world, BlockPosition position) {
        if (this.block == Blocks.AIR) {
            return false;
        }

        Material material = world.getType(position).getBlock().getMaterial();
        boolean flag = !material.isBuildable();
        if (!world.isEmpty(position) && !flag) {
            return false;
        }

        if (world.worldProvider.n() && this.block == Blocks.FLOWING_WATER) {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();

            world.makeSound((float) x + 0.5F, (float) y + 0.5F, (float) z + 0.5F,
                "random.fizz", 0.5F, 2.6F + (world.random.nextFloat() - world.random.nextFloat()) * 0.8F);

            for(int l = 0; l < 8; ++l) {
                world.addParticle(EnumParticle.SMOKE_LARGE, (double) x + Math.random(), (double) y + Math.random(),
                    (double) z + Math.random(), 0.0F, 0.0F, 0.0F);
            }
        } else {
            if (!world.isClientSide && flag && !material.isLiquid()) {
                world.setAir(position, true);
            }

            world.setTypeAndData(position, this.block.getBlockData(), 3);
        }

        return true;
    }
}
