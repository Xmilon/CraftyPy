package net.xmilon.craftpy.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.xmilon.craftpy.content.Defs;

/** These goals run in Minecraft's normal AI scheduler, without Python work on movement or attack frames. */
final class NativeAi {
    private NativeAi() { }
    static boolean configure(SlottedEntity mob, Defs.EntityDef def) {
        if (def.behavior.isEmpty()) return false;
        String profile = Defs.s(def.behavior, "profile", "melee");
        double speed = Defs.f(def.behavior, "speed", 1);
        if (profile.equals("ranged")) mob.addNativeGoal(2, false, new RangedAttackGoal(mob, speed,
                Defs.i(def.behavior, "interval", 40), Defs.f(def.behavior, "range", 16)));
        else mob.addNativeGoal(2, false, new MeleeAttackGoal(mob, speed, false));
        if (profile.equals("follow")) mob.addNativeGoal(3, false, new FollowOwner(mob, speed, Defs.f(def.behavior, "range", 3)));
        if (profile.equals("guard")) {
            var tag = mob.getPersistentData();
            if (!tag.contains("craftpy:guard_home")) tag.putLong("craftpy:guard_home", mob.blockPosition().asLong());
            mob.restrictTo(net.minecraft.core.BlockPos.of(tag.getLong("craftpy:guard_home")), (int) Defs.f(def.behavior, "radius", 12));
            mob.addNativeGoal(3, false, new MoveTowardsRestrictionGoal(mob, speed));
        }
        mob.addNativeGoal(1, true, new HurtByTargetGoal(mob));
        String target = Defs.s(def.behavior, "target", profile.equals("follow") ? "none" : mob.hostile() ? "players" : "hostile");
        if (target.equals("players")) mob.addNativeGoal(2, true, new NearestAttackableTargetGoal<>(mob, Player.class, true));
        if (target.equals("hostile")) mob.addNativeGoal(2, true, new NearestAttackableTargetGoal<>(mob, Mob.class, 10, true, false,
                other -> other instanceof Enemy && (!profile.equals("guard") || mob.isWithinRestriction(other.blockPosition()))));
        return true;
    }

    private static final class FollowOwner extends Goal {
        private final SlottedEntity mob;
        private final double speed, range;
        private Player player;
        private int nextPath;
        FollowOwner(SlottedEntity mob, double speed, double range) {
            this.mob = mob; this.speed = speed; this.range = range; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        @Override public boolean canUse() {
            var controller = mob.getPersistentData().getCompound("craftpy:controller");
            player = controller.hasUUID("player") ? mob.level().getPlayerByUUID(controller.getUUID("player")) : null;
            return player != null && !player.isSpectator() && mob.getTarget() == null && mob.distanceToSqr(player) > range * range;
        }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public void start() { nextPath = 0; }
        @Override public void stop() { mob.getNavigation().stop(); player = null; }
        @Override public void tick() {
            if (player == null) return;
            mob.getLookControl().setLookAt(player, 10, mob.getMaxHeadXRot());
            if (--nextPath <= 0) { nextPath = 10; mob.getNavigation().moveTo(player, speed); }
        }
    }
}
