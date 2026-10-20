package de.leicraftmc.leicraftclient.mixin.client;

import de.leicraftmc.leicraftclient.client.LeiCraftClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the client's name and version in front of the window title, e.g.
 * {@code "LeiCraftClient 2.0.0 | Minecraft* 26.3 - Singleplayer"}.
 *
 * Vanilla builds the title as {@code "Minecraft" [+ "*"] + " " + version} plus
 * the current world. That is kept exactly as vanilla wrote it, so the
 * Minecraft version is not mistaken for the client's. A title that no longer
 * starts with "Minecraft" was changed by another mod and is left alone.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTitleMixin {

	private static final String VANILLA_NAME = "Minecraft";
	private static final String CLIENT_NAME = "LeiCraftClient";

	@Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
	private void leicraftclient$renameWindow(final CallbackInfoReturnable<String> cir) {
		final String title = cir.getReturnValue();
		if (title != null && title.startsWith(VANILLA_NAME)) {
			cir.setReturnValue(CLIENT_NAME + " " + LeiCraftClient.INSTANCE.getVersion() + " | " + title);
		}
	}
}
