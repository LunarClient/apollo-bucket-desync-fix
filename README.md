# Bucket Desync Fix

A demonstration plugin showcasing how to eliminate bucket placement desync in Minecraft **1.7** & **1.8** by leveraging [Apollo](https://github.com/LunarClient/Apollo) client-side ray trace data.

Why is this implemented as a separate plugin and not directly within Apollo?
1. Apollo is designed as a platform-agnostic API. Directly modifying _NMS_ (like replacing bucket logic) is outside its scope to maintain compatibility across different server versions/platforms.
2. Apollo provides the `ApolloPlayerUseItemBucketEvent`, but this event is triggered via the _Bukkit Plugin Message API_ and always runs on the main thread. To prevent server-side desync, the block placement packet must be intercepted **before the server processes it incorrectly**. Achieving this requires handling packets at the network level.

## Disclaimer

This plugin modifies core vanilla bucket mechanics by:
- Replacing the vanilla bucket item implementations in the NMS item registry.
- Canceling default server-side placement logic for buckets.
- Implementing custom placement logic based on client ray trace data.

**Potential Issues:**
- May conflict with other plugins that modify bucket behavior.
- Could cause compatibility issues with anti-cheat plugins.

**Security Implication:**
- Could be exploited by modified clients to interact with blocks they shouldn't reach.
- May allow impossible placements if validation is insufficient.

**Implementation Note:**
The server-sided approach shown here is implemented as a plugin for demonstration. Optimally, this processing should be done directly in the server's packet processing logic (e.g., within the `PlayerConnection` class) rather than through NMS item replacement.

**Use at your own risk.** This plugin is intended as a proof-of-concept.

## Table of Contents

- [The Problem](#the-problem)
- [The Solution](#the-solution)
- [How It Works](#how-it-works)
- [Requirements](#requirements)
- [Setup](#setup)
- [Resources](#resources)

## The Problem

In Minecraft, bucket interactions (water, lava) often appear **desynced** between the client and server, especially in high-latency environments.

### Why Does This Happen?

When a player right-clicks with a bucket:

1. **Client Side:**
    - Performs a ray trace from the player's perspective.
    - Determines which block is being targeted.
    - Sends a `PacketPlayInBlockPlace` packet to the server.
    - Renders the bucket placement immediately.

2. **Server Side:**
    - Receives the packet from the client.
    - **Performs its own independent ray trace** from the server's view of the player position.
    - Places the bucket based on the server's ray trace result.
    - Sends block updates back to the client.

### The Desync Problem

The client and server often disagree on which block was clicked due to network latency: Player position differs between client and server due to packet travel time (latency).

The result is that the server rejects or moves your placement, causing visual glitches, failed placements, or blocks appearing in unexpected locations.

## The Solution

This plugin synchronizes the server's placement logic with the client's view by:

1. **Using Apollo's Packet Enrichment Module** to receive the client's ray trace data.
2. **Replacing vanilla bucket items** with custom implementations that respect client data.

The server trusts the client's ray trace result rather than performing its own, eliminating the desync.

## How It Works

### Client Flow
1. Upon using a bucket, Lunar Client performs a ray trace and captures the exact block/position the player is targeting.
2. Before sending the vanilla `PacketPlayInBlockPlace` packet, the client sends a custom Apollo packet containing the ray trace data.

### Server Flow
1. **Initialization:** Upon starting the server, the default `ItemBucket` in the NMS registry is replaced with our custom implementation, `ApolloItemBucket`.
   > Note: The code shown below is for server version `v1_8_R3`.

   ```java
   private void replaceBucket() {
       Item customBucket = new ApolloItemBucket(Blocks.AIR)
           .c("bucket").c(16);

       Item.REGISTRY.a(325, new MinecraftKey("bucket"), customBucket);
       Item.REGISTRY.a(326, new MinecraftKey("water_bucket"), new ApolloItemBucket(Blocks.FLOWING_WATER)
           .c("bucketWater").c(customBucket));
       Item.REGISTRY.a(327, new MinecraftKey("lava_bucket"), new ApolloItemBucket(Blocks.FLOWING_LAVA)
           .c("bucketLava").c(customBucket));
   }
   ```

2. **Data Reception:** The server receives the Apollo `PlayerUseItemBucketMessage` via the `PacketPlayInCustomPayload` packet and caches the ray trace data for the player.

3. **Packet Interception:** When the vanilla `PacketPlayInBlockPlace` packet arrives:
    - The server checks if it's a bucket usage.
    - If valid client ray trace data is cached, the default server-side placement packet is **cancelled**.
    - This prevents the server from running its standard logic which would use the potentially incorrect server-side ray trace.

4. **Custom Processing:** The plugin manually processes the placement on the main thread:
    - It simulates firing `PlayerInteractEvent` using the **client's ray trace location**. This ensures other plugins see the interaction at the correct location.
    - If the event is not cancelled, it calls `PlayerInteractManager#useItem`.

5. **Placement Logic:** The `useItem` call eventually triggers our custom `ApolloItemBucket` logic:
    > Note: The NMS code shown below is for version `v1_8_R3`.

```diff
 public class ApolloItemBucket extends ItemBucket {
 
     public ItemStack a(ItemStack item, World world, EntityHuman entity) {
         boolean flag = this.block == Blocks.AIR;
 
-        MovingObjectPosition movingobjectposition = this.a(world, entity, flag);
+        // Desync fix start
+        MovingObjectPosition movingobjectposition = null;
+
+        // Try to get the client's ray trace result first
+        BucketPlaceManager bucketManager = DesyncPlugin.getInstance().getBucketPlaceManager();
+        if (bucketManager != null && entity instanceof EntityPlayer) {
+            EntityPlayer player = (EntityPlayer) entity;
+            RayTraceResult clientRayTrace = bucketManager.getAndRemove(player.getUniqueID());
+
+            if (clientRayTrace instanceof BlockHitResult) {
+                BlockHitResult blockHit = (BlockHitResult) clientRayTrace;
+                movingobjectposition = bucketManager.convertToMovingObjectPosition(blockHit);
+            }
+        }
+        
+        // Fallback to server-side ray trace if no client data available
+        if (movingobjectposition == null) {
+            movingobjectposition = this.a(world, entity, flag);
+        }
+        // Desync fix end
+
+        // ... the rest of the vanilla implementation ...
     }
 }
```

### Requirements
- [PacketEvents](https://github.com/retrooper/packetevents)
- [Apollo](https://github.com/LunarClient/Apollo) v1.2.2+
- [LunarClient Version](https://www.lunarclient.com/download) (TODO)

### Server Setup
1. Install **Apollo**, **PacketEvents**, and this **DesyncPlugin** in your server's `plugins` folder.
2. Configure Apollo:
    - Ensure the **Packet Enrichment** module is enabled in Apollo's `config.yml`.

```diff
--- /plugins/Apollo-Bukkit/config.yml
+++ /plugins/Apollo-Bukkit/config.yml
@@
     packet_enrichment:
         # Set to 'true' to enable this module, otherwise set 'false'.
-        enable: false
+        enable: true
         player-use-item-bucket:
            # Set to 'true' to have the client send an additional player use item bucket packet to the server, otherwise 'false'.
-           send-packet: false
+           send-packet: true
             # If 'true', Apollo fires the player use item bucket event on the main thread. Disable this and handle the packet yourself if you require asynchronous or off-thread processing.
             fire-apollo-event: false
```

## Resources

- [Apollo Documentation](https://lunarclient.dev/apollo/introduction)
- [PacketEvents](https://github.com/retrooper/packetevents)

