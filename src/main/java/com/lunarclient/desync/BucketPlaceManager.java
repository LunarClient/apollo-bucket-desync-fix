package com.lunarclient.desync;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.lunarclient.apollo.Apollo;
import com.lunarclient.apollo.common.location.ApolloBlockLocation;
import com.lunarclient.apollo.common.location.ApolloLocation;
import com.lunarclient.apollo.libs.protobuf.Any;
import com.lunarclient.apollo.module.packetenrichment.raytrace.BlockHitResult;
import com.lunarclient.apollo.module.packetenrichment.raytrace.Direction;
import com.lunarclient.apollo.module.packetenrichment.raytrace.RayTraceResult;
import com.lunarclient.apollo.network.NetworkTypes;
import com.lunarclient.apollo.packetenrichment.v1.PlayerUseItemBucketMessage;
import com.lunarclient.apollo.player.ApolloPlayer;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EnumDirection;
import net.minecraft.server.v1_8_R3.MovingObjectPosition;
import net.minecraft.server.v1_8_R3.Vec3D;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_8_R3.event.CraftEventFactory;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BucketPlaceManager extends PacketListenerAbstract implements Listener {

    private final Map<UUID, RayTraceResult> rayTraceCache = new ConcurrentHashMap<>();
    private final EnumSet<Material> buckets = EnumSet.of(Material.BUCKET, Material.WATER_BUCKET, Material.LAVA_BUCKET);

    public BucketPlaceManager() {
        super(PacketListenerPriority.HIGH);

        Bukkit.getPluginManager().registerEvents(this, DesyncPlugin.getInstance());
    }

    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent event) {
        this.rayTraceCache.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Player player = event.getPlayer();
        PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            this.handleBlockPlacement(player, event);
        } else if (type == PacketType.Play.Client.PLUGIN_MESSAGE) {
            this.handlePluginMessage(player, event);
        }
    }

    /**
     * Handles incoming Apollo plugin messages from the client and extracts the client-side
     * ray trace result that is sent prior to the {@link net.minecraft.server.v1_8_R3.PacketPlayInBlockPlace} packet.
     *
     * <p>Note: We cannot use the Bukkit API for plugin messages here because it is processed on the main thread.</p>
     *
     * @param player the player that sent the packet
     * @param event the plugin message event
     */
    private void handlePluginMessage(Player player, PacketReceiveEvent event) {
        if (!Apollo.getPlayerManager().hasSupport(player.getUniqueId())) {
            return;
        }

        WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);

        if (!packet.getChannelName().equals("lunar:apollo")) {
            return;
        }

        Optional<ApolloPlayer> apolloPlayerOpt = Apollo.getPlayerManager().getPlayer(player.getUniqueId());
        if (!apolloPlayerOpt.isPresent()) {
            return;
        }

        try {
            byte[] data = packet.getData();
            Any any = Any.parseFrom(data);

            if (any.is(PlayerUseItemBucketMessage.class)) {
                PlayerUseItemBucketMessage message = any.unpack(PlayerUseItemBucketMessage.class);
                this.rayTraceCache.put(player.getUniqueId(), NetworkTypes.fromProtobuf(message.getRayTraceResult()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles the client block placement packet and, if applicable, replaces the server's
     * default placement logic with based on the received client-side ray trace result.
     *
     * @param player the player that sent the packet
     * @param event the plugin message event
     */
    private void handleBlockPlacement(Player player, PacketReceiveEvent event) {
        if (!Apollo.getPlayerManager().hasSupport(player.getUniqueId())) {
            return;
        }

        WrapperPlayClientPlayerBlockPlacement blockPlacement = new WrapperPlayClientPlayerBlockPlacement(event);
        if (blockPlacement.getFaceId() != 255) {
            return;
        }

        ItemStack item = player.getItemInHand();
        if (item == null || !this.buckets.contains(item.getType())) {
            return;
        }

        // Check if we have client ray trace
        RayTraceResult clientRayTrace = this.rayTraceCache.get(player.getUniqueId());

        if (clientRayTrace == null) {
            return;
        }

        event.setCancelled(true);

        // Process on main thread
        Bukkit.getScheduler().runTask(DesyncPlugin.getInstance(), () -> {
            try {
                this.processBucketPlacement(player, clientRayTrace);
            } finally {
                this.rayTraceCache.remove(player.getUniqueId());
            }
        });
    }

    /**
     * Processes a bucket use action using the client-side ray trace result instead of relying on the server's own.
     *
     * <p>This method reproduces Bukkit's interaction flow by manually firing the appropriate
     * {@link PlayerInteractEvent} (either {@code RIGHT_CLICK_AIR} or {@code RIGHT_CLICK_BLOCK})
     * based on the provided {@link RayTraceResult}, and respects the event's cancellation
     * result before performing the actual item use.</p>
     *
     * @param player the player that used the bucket
     * @param rayTraceResult the client-side ray trace result
     */
    private void processBucketPlacement(Player player, RayTraceResult rayTraceResult) {
        EntityPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        net.minecraft.server.v1_8_R3.ItemStack itemStack = nmsPlayer.inventory.getItemInHand();

        boolean cancelled;
        if (!(rayTraceResult instanceof BlockHitResult)) {
            PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(nmsPlayer, Action.RIGHT_CLICK_AIR, itemStack);
            cancelled = (event.useItemInHand() == Event.Result.DENY);
        } else if (nmsPlayer.playerInteractManager.firedInteract) {
            nmsPlayer.playerInteractManager.firedInteract = false;
            cancelled = nmsPlayer.playerInteractManager.interactResult;
        } else {
            BlockHitResult blockHitResult = (BlockHitResult) rayTraceResult;
            ApolloBlockLocation blockLocation = blockHitResult.getBlockLocation();
            BlockPosition blockPosition = new BlockPosition(blockLocation.getX(), blockLocation.getY(), blockLocation.getZ());

            PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(nmsPlayer, Action.RIGHT_CLICK_BLOCK,
                blockPosition, this.convertDirection(blockHitResult.getDirection()), itemStack, true);

            cancelled = event.useItemInHand() == Event.Result.DENY;
        }

        if (!cancelled) {
            nmsPlayer.playerInteractManager.useItem(nmsPlayer, nmsPlayer.world, itemStack);
        }
    }

    private EnumDirection convertDirection(Direction apolloDirection) {
        switch (apolloDirection) {
            case DOWN:
                return EnumDirection.DOWN;
            case UP:
                return EnumDirection.UP;
            case NORTH:
                return EnumDirection.NORTH;
            case SOUTH:
                return EnumDirection.SOUTH;
            case WEST:
                return EnumDirection.WEST;
            case EAST:
                return EnumDirection.EAST;
            default:
                return EnumDirection.UP;
        }
    }

    public MovingObjectPosition convertToMovingObjectPosition(BlockHitResult blockHit) {
        ApolloLocation hitLocation = blockHit.getHitLocation();
        Vec3D hitVec = new Vec3D(hitLocation.getX(), hitLocation.getY(), hitLocation.getZ());

        EnumDirection direction = this.convertDirection(blockHit.getDirection());

        ApolloBlockLocation blockLocation = blockHit.getBlockLocation();
        BlockPosition blockPos = new BlockPosition(blockLocation.getX(), blockLocation.getY(), blockLocation.getZ());

        return new MovingObjectPosition(hitVec, direction, blockPos);
    }

    public RayTraceResult getAndRemove(UUID playerUuid) {
        return this.rayTraceCache.remove(playerUuid);
    }
    
}