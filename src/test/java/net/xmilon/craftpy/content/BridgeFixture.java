package net.xmilon.craftpy.content;

import java.util.List;

/** A class the Java bridge tests drive; it sits in a CraftPy package scripts may reach. */
public class BridgeFixture {
    public int count = 3;
    public static final String NAME = "fx";
    public final String locked = "x";
    public enum Color { RED, GREEN }

    public BridgeFixture() {
    }

    public BridgeFixture(int start, String label) {
        count = start;
    }

    public int add(int a, int b) {
        return a + b;
    }

    public double add(double a, double b) {
        return a + b;
    }

    public String paint(Color color) {
        return color.name();
    }

    public int sum(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }

    public int size(List<Object> items) {
        return items.size();
    }

    public static BridgeFixture make() {
        return new BridgeFixture(9, "made");
    }
}
