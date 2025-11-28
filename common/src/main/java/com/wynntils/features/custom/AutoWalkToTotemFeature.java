package com.wynntils.features.custom;

import com.wynntils.core.components.Models;
import com.wynntils.core.consumers.features.Feature;
import com.wynntils.core.consumers.features.properties.RegisterKeyBind;
import com.wynntils.core.keybinds.KeyBind;
import com.wynntils.core.persisted.config.Category;
import com.wynntils.core.persisted.config.ConfigCategory;
import com.wynntils.mc.event.TickEvent;
import com.wynntils.models.abilities.type.ShamanTotem;
import com.wynntils.models.bonustotems.BonusTotem;
import com.wynntils.models.bonustotems.type.BonusTotemType;
import com.wynntils.utils.mc.McUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Position;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

@ConfigCategory(Category.INVENTORY)
public class AutoWalkToTotemFeature extends Feature {
    @RegisterKeyBind
    public final KeyBind autoWalkKeyBind =
            new KeyBind("Auto Walk Towards Nearest Totem", GLFW.GLFW_KEY_F9, true, this::action);
    private final Minecraft client;
    private boolean isWalking = false;
    private Vec3 lastPosition;
    public AutoWalkToTotemFeature() {
        this.client = Minecraft.getInstance();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && isWalking) {
            if (getCenter() != null)
                if (getCenter().distanceTo(player.position()) > 6) {
                    pointPlayerToCoordinates(getCenter());
                    startWalking();
                } else {
                    stopWalking();
                }
            else {
                stopWalking();
            }
        }
    }

    private void startWalking() {
        LocalPlayer player = client.player;
        if (player != null) {
            KeyMapping forwardKey = client.options.keyUp;
            forwardKey.setDown(true);
        }
    }

    private void stopWalking() {
        KeyMapping forwardKey = client.options.keyUp;
        forwardKey.setDown(false);
    }

    private double calculateYaw(double px, double py, double pz, double tx, double ty, double tz) {
        double dx = tx - px;
        double dz = tz - pz;
        return Math.toDegrees(Math.atan2(dx, dz)) * -1;
    }

    private double calculatePitch(double px, double py, double pz, double tx, double ty, double tz) {
        double dx = tx - px;
        double dz = tz - pz;
        double dy = ty - py;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        return Math.toDegrees(Math.atan2(dy, horizontalDistance)) * -1;
    }

    private void pointPlayerToCoordinates(Position targetPos) {
        LocalPlayer player = client.player;
        if (player != null) {
            double yaw = calculateYaw(player.getX(), player.getY(), player.getZ(), targetPos.x(), targetPos.y(), targetPos.z());
            double pitch = calculatePitch(player.getX(), player.getY(), player.getZ(), targetPos.x(), targetPos.y(), targetPos.z());
            player.setYRot((float) (player.getYRot() + yaw) / 2);
            player.setXRot((float) (player.getXRot() + pitch) / 2);
        }
    }
    public Vec3 getCenter() {
        List<Position> totems = Models.BonusTotem.getBonusTotemsByType(BonusTotemType.MOB).stream().map(BonusTotem::getPosition).toList();
        if (totems.isEmpty()) {
            totems = Models.ShamanTotem.getActiveTotems().stream().map(ShamanTotem::getPosition).toList();
        }
        if (totems.isEmpty()) {
            return lastPosition;
        }

        double sumX = 0;
        double sumY = 0;
        double sumZ = 0;

        for (Position totem : totems) {
            sumX += totem.x();
            sumY += totem.y();
            sumZ += totem.z();
        }

        double centerX = sumX / totems.size();
        double centerY = sumY / totems.size();
        double centerZ = sumZ / totems.size();

        lastPosition = new Vec3(centerX, centerY, centerZ);
        return new Vec3(centerX, centerY, centerZ);
    }
    public void action() {
        if (!isWalking) {
            if (getCenter() != null) {
                McUtils.sendMessageToClient(Component.literal("Enabled auto walk"));
                pointPlayerToCoordinates(getCenter());
                isWalking = true;
                startWalking();
            } else
                McUtils.sendMessageToClient(Component.literal("No totems found"));
        } else {
            McUtils.sendMessageToClient(Component.literal("Disable auto walk"));
            stopWalking();
            isWalking = false;
        }
    }
}
