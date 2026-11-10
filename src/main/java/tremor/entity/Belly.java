package tremor.entity;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import tremor.Tremor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What the tremor keeps of what it devours ({@link Devour}), in the overworld's storage
 * ({@code data/tremor_belly.dat}), so that it can be won back later: mobs that matter to someone, alive and whole
 * (their full data as the game saves them), and the things players dropped. The rules of what is kept and how much are
 * {@link BellyRules}. Server thread only. {@code /tremor belly <list|clear|spit <index>>} looks into it.
 */
public final class Belly extends SavedData {
    static final String NAME = Tremor.MODID + "_belly";
    /** Entity types kept whatever they are, for modpack authors (shipped empty). */
    public static final TagKey<EntityType<?>> KEEPS = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "belly_keeps"));
    private static final SavedData.Factory<Belly> FACTORY = new SavedData.Factory<>(Belly::new, Belly::load);

    /** A kept mob: its type, its data without the id, its owner and name if any, where and when it was taken. */
    private record KeptMob(String type, CompoundTag data, UUID owner, String name, String dimension, Vec3 at,
                           long time) {
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("type", type);
            tag.put("data", data);
            if (owner != null) {
                tag.putUUID("owner", owner);
            }
            if (name != null) {
                tag.putString("name", name);
            }
            tag.putString("dimension", dimension);
            tag.putDouble("x", at.x);
            tag.putDouble("y", at.y);
            tag.putDouble("z", at.z);
            tag.putLong("time", time);
            return tag;
        }

        static KeptMob load(CompoundTag tag) {
            return new KeptMob(tag.getString("type"), tag.getCompound("data"),
                    tag.hasUUID("owner") ? tag.getUUID("owner") : null,
                    tag.contains("name") ? tag.getString("name") : null, tag.getString("dimension"),
                    new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")), tag.getLong("time"));
        }
    }

    /** A kept stack: the stack, the player who dropped it (null for a death drop stamped before its player was), when. */
    private record KeptItem(ItemStack stack, UUID player, long time) {
        CompoundTag save(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.put("stack", stack.save(registries));
            if (player != null) {
                tag.putUUID("player", player);
            }
            tag.putLong("time", time);
            return tag;
        }

        static KeptItem load(CompoundTag tag, HolderLookup.Provider registries) {
            ItemStack stack = ItemStack.parse(registries, tag.getCompound("stack"))
                    .orElseThrow(() -> new IllegalArgumentException("unreadable stack"));
            return new KeptItem(stack, tag.hasUUID("player") ? tag.getUUID("player") : null, tag.getLong("time"));
        }
    }

    private final List<KeptMob> mobs = new ArrayList<>();
    private final List<KeptItem> items = new ArrayList<>();

    private Belly() {
    }

    /** The server's belly, created empty (and not written until something is kept) if there is none yet. */
    private static Belly get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** Whether the tremor keeps {@code mob} when it devours it, rather than digesting it ({@link BellyRules#keepsMob}). */
    static boolean keeps(Mob mob) {
        return BellyRules.keepsMob(false, mob.hasCustomName(),
                mob instanceof OwnableEntity pet && pet.getOwnerUUID() != null, mob instanceof AbstractVillager,
                mob.getType().is(KEEPS));
    }

    /**
     * Keeps {@code mob} as it is now, whole (without passengers, off its leash): called as it is taken, before it is
     * frozen to sink, so that it comes back as it was.
     */
    static void keep(ServerLevel level, Mob mob) {
        CompoundTag data = mob.saveWithoutId(new CompoundTag());
        data.remove(Entity.PASSENGERS_TAG);
        data.remove(net.minecraft.world.entity.Leashable.LEASH_TAG);
        Belly belly = get(level.getServer());
        BellyRules.add(belly.mobs, new KeptMob(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(), data,
                mob instanceof OwnableEntity pet ? pet.getOwnerUUID() : null,
                mob.hasCustomName() ? mob.getCustomName().getString() : null, level.dimension().location().toString(),
                mob.position(), level.getGameTime()), BellyRules.MAX_MOBS);
        belly.setDirty();
    }

    /** Keeps the stack of {@code item}, dropped by {@code player}. */
    static void keep(ServerLevel level, ItemEntity item, UUID player) {
        if (item.getItem().isEmpty()) {
            return;
        }
        Belly belly = get(level.getServer());
        BellyRules.add(belly.items, new KeptItem(item.getItem().copy(), player, level.getGameTime()),
                BellyRules.MAX_ITEMS);
        belly.setDirty();
    }

    /** {@code /tremor belly <list|clear|spit <index>>}: indices run over the mobs, then over the stacks. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("belly")
                .then(Commands.literal("list").executes(Belly::list))
                .then(Commands.literal("clear").executes(Belly::clear))
                .then(Commands.literal("spit").then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .executes(ctx -> spit(ctx, IntegerArgumentType.getInteger(ctx, "index")))));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Belly belly = get(source.getServer());
        long now = source.getServer().overworld().getGameTime();
        source.sendSuccess(() -> Component.literal("Belly: " + belly.mobs.size() + " mobs, " + belly.items.size()
                + " stacks"), false);
        int index = 0;
        for (KeptMob mob : belly.mobs) {
            String line = String.format(Locale.ROOT, "%d mob %s%s owner %s, %s ago at %s %.0f %.0f %.0f", index++,
                    mob.type, mob.name != null ? " \"" + mob.name + "\"" : "", mob.owner, age(now - mob.time),
                    mob.dimension, mob.at.x, mob.at.y, mob.at.z);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        for (KeptItem item : belly.items) {
            String line = String.format(Locale.ROOT, "%d item %d %s owner %s, %s ago", index++, item.stack.getCount(),
                    BuiltInRegistries.ITEM.getKey(item.stack.getItem()), item.player, age(now - item.time));
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return belly.mobs.size() + belly.items.size();
    }

    private static String age(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        return seconds < 60 ? seconds + "s" : seconds / 60 + "m" + seconds % 60 + "s";
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        Belly belly = get(ctx.getSource().getServer());
        int count = belly.mobs.size() + belly.items.size();
        belly.mobs.clear();
        belly.items.clear();
        belly.setDirty();
        ctx.getSource().sendSuccess(() -> Component.literal("Belly cleared: " + count + " entries"), false);
        return count;
    }

    /** Puts entry {@code index} back into the world where the command runs, as it was, and forgets it. */
    private static int spit(CommandContext<CommandSourceStack> ctx, int index) {
        CommandSourceStack source = ctx.getSource();
        Belly belly = get(source.getServer());
        ServerLevel level = source.getLevel();
        Vec3 at = source.getPosition();
        if (index < belly.mobs.size()) {
            KeptMob mob = belly.mobs.get(index);
            CompoundTag data = mob.data.copy();
            data.putString("id", mob.type);
            Entity entity = EntityType.create(data, level).orElse(null);
            if (entity == null) {
                source.sendFailure(Component.literal("Cannot make a " + mob.type + " of entry " + index));
                return 0;
            }
            if (level.getEntity(entity.getUUID()) != null) {
                entity.setUUID(UUID.randomUUID()); // its old self is still about (spat out before, or never gone)
            }
            entity.moveTo(at.x, at.y, at.z, entity.getYRot(), entity.getXRot());
            level.addFreshEntity(entity);
            belly.mobs.remove(index);
            belly.setDirty();
            source.sendSuccess(() -> Component.literal("Spat out " + entity.getName().getString()), false);
            return 1;
        }
        int item = index - belly.mobs.size();
        if (item < belly.items.size()) {
            KeptItem kept = belly.items.get(item);
            ItemEntity entity = new ItemEntity(level, at.x, at.y, at.z, kept.stack.copy());
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
            belly.items.remove(item);
            belly.setDirty();
            source.sendSuccess(() -> Component.literal("Spat out " + kept.stack.getCount() + " "
                    + kept.stack.getHoverName().getString()), false);
            return 1;
        }
        source.sendFailure(Component.literal("No entry " + index + " in the belly"));
        return 0;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag mobList = new ListTag();
        for (KeptMob mob : mobs) {
            mobList.add(mob.save());
        }
        ListTag itemList = new ListTag();
        for (KeptItem item : items) {
            itemList.add(item.save(registries));
        }
        tag.put("mobs", mobList);
        tag.put("items", itemList);
        return tag;
    }

    private static Belly load(CompoundTag tag, HolderLookup.Provider registries) {
        Belly belly = new Belly();
        for (Tag entry : tag.getList("mobs", Tag.TAG_COMPOUND)) {
            belly.mobs.add(KeptMob.load((CompoundTag) entry));
        }
        for (Tag entry : tag.getList("items", Tag.TAG_COMPOUND)) {
            try {
                belly.items.add(KeptItem.load((CompoundTag) entry, registries));
            } catch (RuntimeException e) {
                Tremor.LOGGER.error("Dropping an unreadable stack of the belly {}", entry, e); // its mod is gone
            }
        }
        return belly;
    }
}
