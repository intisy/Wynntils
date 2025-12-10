package com.wynntils.features.macro;

import com.wynntils.core.consumers.features.Feature;
import com.wynntils.core.consumers.features.properties.RegisterKeyBind;
import com.wynntils.core.keybinds.KeyBind;
import com.wynntils.core.persisted.config.Category;
import com.wynntils.core.persisted.config.ConfigCategory;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.TickEvent;
import com.wynntils.utils.mc.LoreUtils;
import com.wynntils.utils.mc.McUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ConfigCategory(Category.MACRO)
public class AutoGatherFeature extends Feature {
    private enum Mode { MINING, WOODCUTTING, FARMING, FISHING }

    private static final Map<Mode, List<BlockPos>> POINTS = Map.of(
            Mode.MINING, List.of(),
            Mode.WOODCUTTING, List.of(),
            Mode.FARMING, List.of(),
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

    private static final Map<Mode, String> TOOL_NAMES = Map.of(
            Mode.MINING, "Gathering Pickaxe",
            Mode.WOODCUTTING, "Gathering Axe",
            Mode.FARMING, "Gathering Scythe",
            Mode.FISHING, "Gathering Rod"
    );

    private static final Pattern DURABILITY_PATTERN = Pattern.compile("\\[(\\d+)/(\\d+) Durability\\]");

    @RegisterKeyBind
    private final KeyBind keyMining = new KeyBind("Gather: Mining", GLFW.GLFW_KEY_UNKNOWN, true, () -> setMode(Mode.MINING));

    @RegisterKeyBind
    private final KeyBind keyWoodcutting = new KeyBind("Gather: Woodcutting", GLFW.GLFW_KEY_UNKNOWN, true, () -> setMode(Mode.WOODCUTTING));

    @RegisterKeyBind
    private final KeyBind keyFarming = new KeyBind("Gather: Farming", GLFW.GLFW_KEY_UNKNOWN, true, () -> setMode(Mode.FARMING));

    @RegisterKeyBind
    private final KeyBind keyFishing = new KeyBind("Gather: Fishing", GLFW.GLFW_KEY_UNKNOWN, true, () -> setMode(Mode.FISHING));

    private final Minecraft client;
    private boolean isActive = false;
    private Mode currentMode = null;
    private final Map<Mode, Map<BlockPos, Long>> cooldowns = new ConcurrentHashMap<>();
    private static final int MIN_CPS = 2;
    private static final int MAX_CPS = 6;
    private static final int MIN_ATTACK_DISTANCE = 2;
    private static final int MAX_ATTACK_DISTANCE = 6;
    private static final long ATTACK_TIMEOUT_MS = 5000;
    private static final long TARGET_COOLDOWN_MS = 60000;
    private static final double MAX_PATH_DISTANCE = 100.0;

    private final Random random = new Random();
    private long nextAttackTime = 0;
    private double currentAttackDistance = 0;
    private long inReachStartTime = 0;
    private boolean inReach = false;
    private BlockPos currentTarget = null;
    private boolean releaseAttack = false;

    public AutoGatherFeature() {
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
        configureSmoothLook(true);
        McUtils.sendMessageToClient(Component.literal("Enabled Auto Gather: " + newMode.name()));
    }

    @SubscribeEvent
    public void onSoundPlayed(com.wynntils.mc.event.SoundPlayedEvent event) {
        if (!isActive || currentTarget == null || currentMode == null) return;

        if (event.getSoundInstance().getLocation().getPath().equals("entity.experience_orb.pickup")) {
            cooldowns.get(currentMode).put(currentTarget, System.currentTimeMillis());
            currentTarget = null;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (releaseAttack && client.options != null) {
            client.options.keyAttack.setDown(false);
            releaseAttack = false;
        }

        if (!isActive || client.player == null || currentMode == null) return;

        if (!findAndEquipTool(currentMode)) {
            isActive = false;
            stopBaritone();
            McUtils.sendMessageToClient(Component.literal("No valid tool found, stopping."));
            return;
        }

        List<BlockPos> points = POINTS.get(currentMode);
        if (points == null || points.isEmpty()) return;

        if (currentTarget == null) {
            findNextTarget(points);
            return;
        }

        double distSq = client.player.distanceToSqr(Vec3.atCenterOf(currentTarget));

        if (distSq < currentAttackDistance * currentAttackDistance) {
            if (!inReach) {
                inReach = true;
                inReachStartTime = System.currentTimeMillis();
            }
            hitTarget(currentTarget);
        }

        if (inReach && System.currentTimeMillis() - inReachStartTime > ATTACK_TIMEOUT_MS) {
            cooldowns.get(currentMode).put(currentTarget, System.currentTimeMillis());
            currentTarget = null;
            inReach = false;
            return;
        }

        if (!isBaritoneActive()) {
            moveToTarget(currentTarget);
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

            if (now - lastHit > TARGET_COOLDOWN_MS) {
                double d = player.blockPosition().distSqr(pos);
                if (d < nearestDistSq && Math.sqrt(d) <= MAX_PATH_DISTANCE) {
                    nearestDistSq = d;
                    nearest = pos;
                }
            }
        }

        if (nearest != null) {
            currentTarget = nearest;
            currentAttackDistance = MIN_ATTACK_DISTANCE + random.nextDouble() * (MAX_ATTACK_DISTANCE - MIN_ATTACK_DISTANCE);
            inReach = false;
            moveToTarget(nearest);
        }
    }

    private void hitTarget(BlockPos target) {
        if (client.player == null) return;
        if (System.currentTimeMillis() < nextAttackTime) return;

        smoothLookAt(Vec3.atCenterOf(target));

        client.player.swing(InteractionHand.MAIN_HAND);
        client.options.keyAttack.setDown(true);
        releaseAttack = true;

        int minDelay = 1000 / MAX_CPS;
        int maxDelay = 1000 / MIN_CPS;

        long delay = minDelay + random.nextInt(maxDelay - minDelay);
        nextAttackTime = System.currentTimeMillis() + delay;
    }

    private void smoothLookAt(Vec3 targetPos) {
        try {
            Object baritone = getPrimaryBaritone();
            Method getLook = baritone.getClass().getMethod("getLookBehavior");
            Object lookBehavior = getLook.invoke(baritone);

            Class<?> rotationClass = Class.forName("baritone.api.utils.Rotation");
            Vec3 eyePos = client.player.getEyePosition();
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
            client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, targetPos);
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

    private void moveToTarget(BlockPos pos) {
        if (client.player == null) return;
        double dist = Math.sqrt(client.player.blockPosition().distSqr(pos));
        if (dist > MAX_PATH_DISTANCE) return;

        try {
            Object baritone = getPrimaryBaritone();
            Method getProcess = baritone.getClass().getMethod("getCustomGoalProcess");
            Object process = getProcess.invoke(baritone);

            Class<?> goalNearClass = Class.forName("baritone.api.pathing.goals.GoalNear");
            Object goal = goalNearClass.getConstructor(BlockPos.class, int.class).newInstance(pos, 0);

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

    private boolean findAndEquipTool(Mode mode) {
        String toolName = TOOL_NAMES.get(mode);
        if (toolName == null) return false;

        List<ItemStack> items = McUtils.inventory().items;
        int bestSlot = -1;
        int bestDurability = 0;

        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item.isEmpty()) continue;

            String name = StyledText.fromComponent(item.getHoverName()).getString();
            if (!name.contains(toolName)) continue;

            int durability = getToolDurability(item);
            if (durability > 0 && durability > bestDurability) {
                bestDurability = durability;
                bestSlot = i;
            }
        }

        if (bestSlot == -1) return false;

        if (bestSlot < 9) {
            McUtils.player().getInventory().selected = bestSlot;
        } else {
            int hotbarSlot = McUtils.player().getInventory().selected;
            com.wynntils.utils.wynn.InventoryUtils.sendInventorySlotMouseClick(
                    bestSlot, com.wynntils.utils.wynn.InventoryUtils.MouseClickType.LEFT_CLICK);
            com.wynntils.utils.wynn.InventoryUtils.sendInventorySlotMouseClick(
                    hotbarSlot, com.wynntils.utils.wynn.InventoryUtils.MouseClickType.LEFT_CLICK);
            com.wynntils.utils.wynn.InventoryUtils.sendInventorySlotMouseClick(
                    bestSlot, com.wynntils.utils.wynn.InventoryUtils.MouseClickType.LEFT_CLICK);
        }

        return true;
    }

    private int getToolDurability(ItemStack item) {
        java.util.LinkedList<StyledText> lore = LoreUtils.getLore(item);
        if (lore.isEmpty()) return -1;

        StyledText lastLine = lore.get(lore.size() - 1);
        Matcher matcher = DURABILITY_PATTERN.matcher(lastLine.getString());
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }
}