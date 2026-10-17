package de.leicraftmc.leicraftclient.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the client's name in the window title.
 *
 * Vanilla builds the title as {@code "Minecraft" [+ "*"] + " " + version} plus
 * the current world, so only the leading product name is swapped -- the
 * modified marker, version and world suffix are left exactly as vanilla wrote
 * them.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTitleMixin {

	private static final String VANILLA_NAME = "Minecraft";
	private static final String CLIENT_NAME = "LeiCraftClient";

	@Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
	private void leicraftclient$renameWindow(final CallbackInfoReturnable<String> cir) {
		final String title = cir.getReturnValue();
		if (title != null && title.startsWith(VANILLA_NAME)) {
			cir.setReturnValue(CLIENT_NAME + title.substring(VANILLA_NAME.length()));
		}
	}
}
