package com.metallum.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Boots on the Metal backend, renders the title screen and a world through Sodium, and saves
 * screenshots under {@code build/run/clientGameTest/screenshots} for inspection.
 */
public final class MetallumClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(final ClientGameTestContext context) {
        String backend = context.computeOnClient(client -> RenderSystem.getDevice().getDeviceInfo().backendName());
        if (!"Metal".equals(backend)) {
            throw new AssertionError("Expected the Metal backend, got " + backend);
        }
        // Let the title screen widgets finish fading in.
        context.waitTicks(60);
        context.takeScreenshot("metallum-title");

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            // Opaque, cutout and translucent geometry in view: water and stained glass exercise
            // the order-independent transparency pass (multiple render targets).
            server.runCommand("fill -6 -61 3 6 -61 12 minecraft:water");
            server.runCommand("fill -3 -60 6 3 -57 6 minecraft:red_stained_glass");
            server.runCommand("fill -5 -60 9 -2 -57 9 minecraft:oak_leaves");
            server.runCommand("setblock 4 -60 8 minecraft:torch");
            server.runCommand("tp @a 0 -59 0 0 15");
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(40);
            context.takeScreenshot("metallum-world");
        }
    }
}
