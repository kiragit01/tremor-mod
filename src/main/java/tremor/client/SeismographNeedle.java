package tremor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.item.Sensing;
import tremor.item.TremorItems;

/**
 * The needle of the seismograph (SPEC 15, stage 5): the item property {@code tremor:angle} (0..1 of a turn, 0 straight
 * ahead) its model picks one of 16 faces by. It points at the entity this client knows of
 * ({@link ClientTremor#presence}) within {@code items.seismographRange} blocks of whoever holds it, trembling the more
 * the angrier the entity is ({@link Sensing#tremble}); with none in range it turns slowly round and round.
 */
public final class SeismographNeedle {
    public static final ResourceLocation ANGLE = ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "angle");
    /** Turns per tick of the idle needle. */
    private static final double IDLE_TURN = 1.0 / 160;

    private SeismographNeedle() {
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(TremorItems.SEISMOGRAPH.get(), ANGLE,
                SeismographNeedle::angle));
    }

    private static float angle(ItemStack stack, ClientLevel level, LivingEntity holder, int seed) {
        Entity who = holder != null ? holder : stack.getEntityRepresentation();
        if (who == null) {
            who = Minecraft.getInstance().player;
        }
        ClientLevel world = level != null ? level : Minecraft.getInstance().level;
        if (who == null || world == null) {
            return 0;
        }
        long time = world.getGameTime();
        ClientTremor.Presence presence = ClientTremor.presence();
        if (presence != null && who.level() == world) {
            Vec3 at = presence.center();
            double dx = at.x() - who.getX();
            double dz = at.z() - who.getZ();
            if (dx * dx + dz * dz <= sq(TremorConfig.COMMON.seismographRange.getAsDouble())) {
                double bearing = Math.atan2(dz, dx) / (2 * Math.PI);
                double facing = (who.getVisualRotationYInDegrees() + 90) / 360.0;
                double tremble = Sensing.tremble(presence.stage()) * Math.sin(time * 1.7 + seed);
                return (float) Mth.positiveModulo(bearing - facing + tremble, 1.0);
            }
        }
        return (float) Mth.positiveModulo(time * IDLE_TURN + seed * 0.13, 1.0);
    }

    private static double sq(double x) {
        return x * x;
    }
}
