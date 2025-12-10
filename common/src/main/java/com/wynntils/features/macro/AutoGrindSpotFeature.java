package com.wynntils.features.macro;

import com.wynntils.core.components.Models;
import com.wynntils.core.consumers.features.Feature;
import com.wynntils.core.consumers.features.properties.RegisterKeyBind;
import com.wynntils.core.keybinds.KeyBind;
import com.wynntils.core.persisted.config.Category;
import com.wynntils.core.persisted.config.ConfigCategory;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.TickEvent;
import com.wynntils.models.spells.type.SpellDirection;
import com.wynntils.utils.mc.McUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

@ConfigCategory(Category.MACRO)
public class AutoGrindSpotFeature extends Feature {

    @RegisterKeyBind
    private final KeyBind autoGrindKeyBind =
            new KeyBind("Auto Grind Spot", GLFW.GLFW_KEY_UNKNOWN, true, this::toggleActive);

    private final Minecraft client;
    private boolean isActive = false;
    private BlockPos currentGrindTarget = null;
    private Phase currentPhase = Phase.IDLE;

    private long nextAttackTime = 0;
    private static final int MIN_CPS = 2;
    private static final int MAX_CPS = 6;
    private final Random random = new Random();
    private boolean releaseAttack = false;

    private long nextSpell1Time = 0;
    private long nextSpell3Time = 0;
    private static final long SPELL_1_COOLDOWN_MS = 5000;
    private static final long SPELL_3_COOLDOWN_MS = 8000;
    private static final List<SpellDirection> SPELL_1 = List.of(SpellDirection.RIGHT, SpellDirection.LEFT, SpellDirection.RIGHT);
    private static final List<SpellDirection> SPELL_3 = List.of(SpellDirection.RIGHT, SpellDirection.LEFT, SpellDirection.LEFT);

    private enum Phase {
        IDLE,
        NAVIGATING,
        FARMING
    }

    public AutoGrindSpotFeature() {
        this.client = Minecraft.getInstance();
    }

    public void toggleActive() {
        if (isActive) {
            disable();
        } else {
            enable();
        }
    }

    private void enable() {
        isActive = true;
        configureSmoothLook(true);
        McUtils.sendMessageToClient(Component.literal("Enabled Auto Grind Spot"));
        findAndNavigateToNearestSpot();
    }

    private void disable() {
        isActive = false;
        currentPhase = Phase.IDLE;
        currentGrindTarget = null;
        stopBaritone();
        McUtils.sendMessageToClient(Component.literal("Disabled Auto Grind Spot"));
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (releaseAttack && client.options != null) {
            client.options.keyAttack.setDown(false);
            releaseAttack = false;
        }

        if (!isActive || client.player == null) return;

        switch (currentPhase) {
            case NAVIGATING -> handleNavigating();
            case FARMING -> handleFarming();
        }
    }

    private void handleNavigating() {
        if (currentGrindTarget == null) {
            findAndNavigateToNearestSpot();
            return;
        }

        Entity nearbyMonster = findNearestHostileInRadius(5);
        if (nearbyMonster != null) {
            smoothLookAt(nearbyMonster);
            if (Models.Spell.isSpellQueueEmpty()) {
                Models.Spell.addSpellToQueue(SPELL_1);
            }
        }

        double distSq = client.player.blockPosition().distSqr(currentGrindTarget);
        if (distSq < 10 * 10) {
            currentPhase = Phase.FARMING;
            stopBaritone();
            McUtils.sendMessageToClient(Component.literal("Arrived at Grind Spot. Starting to farm."));
        } else {
            if (!isBaritoneActive()) {
                moveToLocation(currentGrindTarget);
            }
        }
    }

    private void handleFarming() {
        if (currentGrindTarget == null) {
            currentPhase = Phase.NAVIGATING;
            return;
        }

        double distFromCenter = client.player.blockPosition().distSqr(currentGrindTarget);
        if (distFromCenter > 50 * 50) {
            McUtils.sendMessageToClient(Component.literal("Too far from grind spot, returning..."));
            currentPhase = Phase.NAVIGATING;
            return;
        }

        Entity target = findNearestHostile();
        if (target != null) {
            double distToMob = client.player.distanceToSqr(target);

            if (distToMob > 4 * 4) {
                if (!isBaritoneActive()) {
                    moveToLocation(target.blockPosition());
                }
            } else {
                stopBaritone();
                smoothLookAt(target);
                attack();
                castSpellsIfReady();
            }
        }
    }

    private void castSpellsIfReady() {
        long now = System.currentTimeMillis();

        if (now >= nextSpell1Time && Models.Spell.isSpellQueueEmpty()) {
            Models.Spell.addSpellToQueue(SPELL_1);
            nextSpell1Time = now + SPELL_1_COOLDOWN_MS;
        } else if (now >= nextSpell3Time && Models.Spell.isSpellQueueEmpty()) {
            Models.Spell.addSpellToQueue(SPELL_3);
            nextSpell3Time = now + SPELL_3_COOLDOWN_MS;
        }
    }

    private Entity findNearestHostile() {
        if (client.level == null || currentGrindTarget == null) return null;

        List<Display.TextDisplay> textDisplays = client.level.getEntitiesOfClass(
                Display.TextDisplay.class,
                client.player.getBoundingBox().inflate(50),
                td -> isHostileNameTag(td)
        );

        Entity nearestMob = null;
        double nearestDist = Double.MAX_VALUE;

        for (Display.TextDisplay nameTag : textDisplays) {
            Entity mob = findMobNearNameTag(nameTag);
            if (mob != null && mob.isAlive()) {
                double dist = client.player.distanceToSqr(mob);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestMob = mob;
                }
            }
        }

        return nearestMob;
    }

    private Entity findNearestHostileInRadius(double radius) {
        if (client.level == null) return null;

        List<Display.TextDisplay> textDisplays = client.level.getEntitiesOfClass(
                Display.TextDisplay.class,
                client.player.getBoundingBox().inflate(radius),
                td -> isHostileNameTag(td)
        );

        Entity nearestMob = null;
        double nearestDist = Double.MAX_VALUE;

        for (Display.TextDisplay nameTag : textDisplays) {
            Entity mob = findMobNearNameTag(nameTag);
            if (mob != null && mob.isAlive()) {
                double dist = client.player.distanceToSqr(mob);
                if (dist < nearestDist && dist <= radius * radius) {
                    nearestDist = dist;
                    nearestMob = mob;
                }
            }
        }

        return nearestMob;
    }

    private boolean isHostileNameTag(Display.TextDisplay textDisplay) {
        Component text = textDisplay.getText();
        if (text == null) return false;

        String str = text.getString();
        StyledText styled = StyledText.fromComponent(text);
        String raw = styled.getString();

        return raw.contains("§c") || raw.contains("§e") || str.contains("§c") || str.contains("§e");
    }

    private Entity findMobNearNameTag(Display.TextDisplay nameTag) {
        if (client.level == null) return null;

        Vec3 nameTagPos = nameTag.position();
        AABB searchBox = new AABB(
                nameTagPos.x - 1, nameTagPos.y - 3, nameTagPos.z - 1,
                nameTagPos.x + 1, nameTagPos.y + 1, nameTagPos.z + 1
        );

        List<Entity> nearbyEntities = client.level.getEntities(nameTag, searchBox, e ->
                !(e instanceof Display.TextDisplay) &&
                !(e instanceof ArmorStand) &&
                e.isAlive()
        );

        return nearbyEntities.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(nameTag)))
                .orElse(null);
    }

    private void findAndNavigateToNearestSpot() {
        var poiService = com.wynntils.core.components.Services.Poi;
        if (poiService == null) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        BlockPos playerPos = player.blockPosition();

        BlockPos nearest = poiService.getCombatPois()
                .filter(poi -> poi.getKind() == com.wynntils.services.map.type.CombatKind.GRIND_SPOTS)
                .map(poi -> poi.getLocation().asLocation())
                .map(loc -> new BlockPos(loc.x, loc.y, loc.z))
                .min(Comparator.comparingDouble(pos -> pos.distSqr(playerPos)))
                .orElse(null);

        if (nearest != null) {
            currentGrindTarget = nearest;
            currentPhase = Phase.NAVIGATING;
            McUtils.sendMessageToClient(Component.literal("Navigating to nearest Grind Spot at " + nearest.toShortString()));
            moveToLocation(nearest);
        } else {
            McUtils.sendMessageToClient(Component.literal("No Grind Spots found!"));
            disable();
        }
    }

    private void attack() {
        if (System.currentTimeMillis() < nextAttackTime) return;

        client.player.swing(InteractionHand.MAIN_HAND);
        client.options.keyAttack.setDown(true);
        releaseAttack = true;

        int minDelay = 1000 / MAX_CPS;
        int maxDelay = 1000 / MIN_CPS;
        long delay = minDelay + random.nextInt(maxDelay - minDelay);
        nextAttackTime = System.currentTimeMillis() + delay;
    }

    private void smoothLookAt(Entity target) {
        try {
            Object baritone = getPrimaryBaritone();
            Method getLook = baritone.getClass().getMethod("getLookBehavior");
            Object lookBehavior = getLook.invoke(baritone);

            Class<?> rotationClass = Class.forName("baritone.api.utils.Rotation");
            Vec3 eyePos = client.player.getEyePosition();
            Vec3 targetPos = target.getEyePosition();
            double dx = targetPos.x - eyePos.x;
            double dy = targetPos.y - eyePos.y;
            double dz = targetPos.z - eyePos.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, dist));
            Object rotation = rotationClass.getConstructor(float.class, float.class).newInstance(yaw, pitch);

            Method updateTarget = lookBehavior.getClass().getMethod("updateTarget", rotationClass, boolean.class);
            updateTarget.invoke(lookBehavior, rotation, true);
        } catch (Exception e) {
            client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        }
    }

    private void configureSmoothLook(boolean enabled) {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Method getSettings = apiClass.getMethod("getSettings");
            Object settings = getSettings.invoke(null);

            Field smoothLook = settings.getClass().getField("smoothLook");
            Object smoothLookSetting = smoothLook.get(settings);
            Method setValue = smoothLookSetting.getClass().getMethod("set", Object.class);
            setValue.invoke(smoothLookSetting, enabled);

            Field smoothLookTicks = settings.getClass().getField("smoothLookTicks");
            Object smoothLookTicksSetting = smoothLookTicks.get(settings);
            setValue = smoothLookTicksSetting.getClass().getMethod("set", Object.class);
            setValue.invoke(smoothLookTicksSetting, 5);
        } catch (Exception ignored) {}
    }

    private Object getPrimaryBaritone() throws Exception {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        Method getProvider = apiClass.getMethod("getProvider");
        Object provider = getProvider.invoke(null);
        Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
        return getPrimary.invoke(provider);
    }

    private boolean isBaritoneActive() {
        try {
            Object baritone = getPrimaryBaritone();
            Method getProcess = baritone.getClass().getMethod("getCustomGoalProcess");
            Object process = getProcess.invoke(baritone);
            Method isActiveMethod = process.getClass().getMethod("isActive");
            return (boolean) isActiveMethod.invoke(process);
        } catch (Exception e) {
            return false;
        }
    }

    private void moveToLocation(BlockPos pos) {
        try {
            Object baritone = getPrimaryBaritone();
            Method getProcess = baritone.getClass().getMethod("getCustomGoalProcess");
            Object process = getProcess.invoke(baritone);

            Class<?> goalNearClass = Class.forName("baritone.api.pathing.goals.GoalNear");
            Object goal = goalNearClass.getConstructor(BlockPos.class, int.class).newInstance(pos, 1);

            Class<?> goalInterface = Class.forName("baritone.api.pathing.goals.Goal");
            Method setGoal = process.getClass().getMethod("setGoalAndPath", goalInterface);
            setGoal.invoke(process, goal);
        } catch (Exception ignored) {}
    }

    private void stopBaritone() {
        try {
            Object baritone = getPrimaryBaritone();
            Method getPathing = baritone.getClass().getMethod("getPathingBehavior");
            Object behavior = getPathing.invoke(baritone);
            Method cancel = behavior.getClass().getMethod("cancelEverything");
            cancel.invoke(behavior);
        } catch (Exception ignored) {}
    }
}
