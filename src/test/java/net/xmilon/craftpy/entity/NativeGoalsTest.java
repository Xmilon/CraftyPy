package net.xmilon.craftpy.entity;

import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.xmilon.craftpy.bridge.JavaAccess;
import net.xmilon.craftpy.security.ModulePermissions;
import net.xmilon.craftpy.security.PermissionStore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeGoalsTest {
    static boolean constructed;
    public static class SafeGoal extends Goal {
        public SafeGoal(Mob mob, int interval) { constructed = true; }
        @Override public boolean canUse() { return false; }
    }
    public static class WrongHostGoal extends Goal {
        public WrongHostGoal(net.minecraft.world.entity.monster.Zombie mob) { constructed = true; }
        @Override public boolean canUse() { return false; }
    }
    @Test void constructorCompatibilityAndBridgePermissionsAreCheckedWithoutExecuting() {
        String owner = "goal_test";
        var permissions = new ModulePermissions(); permissions.java = true;
        PermissionStore.declareOwn(owner, permissions);
        JavaAccess.configure(JavaAccess.Mode.RESTRICTED, List.of());
        try {
            var goals = JsonParser.parseString("[{\"class\":\"" + SafeGoal.class.getName() + "\",\"args\":[20]}]").getAsJsonArray();
            NativeGoals.validate(goals, owner);
            assertFalse(constructed);
            assertThrows(IllegalArgumentException.class, () -> NativeGoals.validate(goals, "unapproved_goal_test"));
            var wrong = JsonParser.parseString("[{\"class\":\"" + WrongHostGoal.class.getName() + "\"}]").getAsJsonArray();
            assertTrue(assertThrows(IllegalArgumentException.class, () -> NativeGoals.validate(wrong, owner)).getMessage().contains("no compatible public constructor"));
            assertFalse(constructed);
        } finally { PermissionStore.declareOwn(owner, null); JavaAccess.configure(JavaAccess.Mode.RESTRICTED, List.of()); }
    }
}
