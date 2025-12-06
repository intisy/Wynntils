package com.wynntils.features.custom;

import com.wynntils.core.consumers.features.Feature;
import com.wynntils.core.consumers.features.properties.RegisterKeyBind;
import com.wynntils.core.keybinds.KeyBind;
import com.wynntils.core.persisted.config.Category;
import com.wynntils.core.persisted.config.ConfigCategory;
import com.wynntils.mc.event.TickEvent;
import com.wynntils.utils.mc.McUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ConfigCategory(Category.MACRO)
public class BaritoneMultiGatherFeature extends Feature {
    private enum Mode { MINING, WOODCUTTING, FARMING, FISHING }

    private static final Map<Mode, List<BlockPos>> POINTS = Map.of(
            Mode.MINING, List.of(
            ),
            Mode.WOODCUTTING, List.of(
            ),
            Mode.FARMING, List.of(
            ),
            Mode.FISHING, List.of(
                    new BlockPos(256, 33, -2180),
                    new BlockPos(251, 33, -2176),
                    new BlockPos(250, 33, -2173),
                    new BlockPos(236, 33, -2123),
                    new BlockPos(231, 33, -2122),
                    new BlockPos(232, 33, -2115),
                    new BlockPos(226, 33, -2113),
                    new BlockPos(223, 33, -2108),
                    new BlockPos(220, 33, -2104),
                    new BlockPos(213, 33, -2104),
                    new BlockPos(206, 33, -2098)
            )
    );

    @RegisterKeyBind
    public final KeyBind keyMining = new KeyBind("Gather: Mining", GLFW.GLFW_KEY_UNKNOWN, true, () -> setMode(Mode.MINING));

    @RegisterKeyBind
    public final KeyBind keyWoodcutting = new KeyBind("Gather: Woodcutting", GLFW.GLFW_KEY_UNKNOWN, true, () -> setMode(Mode.WOODCUTTING));

    @RegisterKeyBind
    public final KeyBind keyFarming = new KeyBind("Gather: Farming", GLFW.GLFW_KEY_UNKNOWN, true, () -> setMode(Mode.FARMING));

    @RegisterKeyBind
    public final KeyBind keyFishing = new KeyBind("Gather: Fishing", GLFW.GLFW_KEY_UNKNOWN, true, () -> setMode(Mode.FISHING));

    private final Minecraft client;
    private boolean isActive = false;
    private Mode currentMode = null;
    private final Map<Mode, Map<BlockPos, Long>> cooldowns = new ConcurrentHashMap<>();
    private BlockPos currentTarget = null;
    private boolean releaseAttack = false;

    public BaritoneMultiGatherFeature() {
        this.client = Minecraft.getInstance();
        for (Mode m : Mode.values()) {
            cooldowns.put(m, new HashMap<>());
        }
    }

    private void setMode(Mode newMode) {
        if (isActive && currentMode == newMode) {
            isActive = false;
            stopBaritone();
            McUtils.sendMessageToClient(Component.literal("Auto Gather Disabled"));
            return;
        }
        isActive = true;
        currentMode = newMode;
        currentTarget = null;
        McUtils.sendMessageToClient(Component.literal("Enabled Auto Gather: " + newMode.name()));
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (releaseAttack && client.options != null) {
            client.options.keyAttack.setDown(false);
            releaseAttack = false;
        }

        if (!isActive || client.player == null || currentMode == null) return;

        List<BlockPos> points = POINTS.get(currentMode);
        if (points == null || points.isEmpty()) return;

        if (currentTarget == null) {
            findNextTarget(points);
            return;
        }

        double distSq = client.player.distanceToSqr(Vec3.atCenterOf(currentTarget));

        if (distSq < 2.5) {
            hitTarget(currentTarget);
        } else {
            if (!isBaritoneActive()) {
                moveToTarget(currentTarget);
            }
        }
    }

    private void findNextTarget(List<BlockPos> points) {
        LocalPlayer player = client.player;
        if (player == null) return;

        long now = System.currentTimeMillis();
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        Map<BlockPos, Long> modeCooldowns = cooldowns.get(currentMode);

        for (BlockPos pos : points) {
            long lastHit = modeCooldowns.getOrDefault(pos, 0L);

            if (now - lastHit > 60000) {
                double d = player.blockPosition().distSqr(pos);
                if (d < nearestDistSq) {
                    nearestDistSq = d;
                    nearest = pos;
                }
            }
        }

        if (nearest != null) {
            currentTarget = nearest;
            moveToTarget(nearest);
        }
    }

    private void hitTarget(BlockPos target) {
        if (client.player == null) return;

        stopBaritone();

        Vec3 targetVec = Vec3.atCenterOf(target);
        client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, targetVec);

        client.player.swing(InteractionHand.MAIN_HAND);
        client.options.keyAttack.setDown(true);
        releaseAttack = true;

        cooldowns.get(currentMode).put(target, System.currentTimeMillis());
        currentTarget = null;
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
            Method isActive = process.getClass().getMethod("isActive");
            return (boolean) isActive.invoke(process);
        } catch (Exception e) {
            return false;
        }
    }

    private void moveToTarget(BlockPos pos) {
        try {
            Object baritone = getPrimaryBaritone();
            Method getProcess = baritone.getClass().getMethod("getCustomGoalProcess");
            Object process = getProcess.invoke(baritone);

            Class<?> goalNearClass = Class.forName("baritone.api.pathing.goals.GoalNear");
            Object goal = goalNearClass.getConstructor(BlockPos.class, int.class).newInstance(pos, 0);

            Class<?> goalInterface = Class.forName("baritone.api.pathing.goals.Goal");
            Method setGoal = process.getClass().getMethod("setGoalAndPath", goalInterface);
            setGoal.invoke(process, goal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopBaritone() {
        try {
            Object baritone = getPrimaryBaritone();
            Method getPathing = baritone.getClass().getMethod("getPathingBehavior");
            Object behavior = getPathing.invoke(baritone);
            Method cancel = behavior.getClass().getMethod("cancelEverything");
            cancel.invoke(behavior);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}