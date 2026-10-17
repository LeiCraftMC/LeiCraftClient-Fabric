package de.leicraftmc.leicraftclient.mixin.client;

import de.leicraftmc.leicraftclient.client.LeiCraftUsers;
import de.leicraftmc.leicraftclient.client.gui.TabListIcon;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks LeiCraft players in the tab list.
 *
 * The badge is prefixed onto the display name rather than drawn into the tab
 * renderer: the name is a {@link Component}, so a single glyph from the mod's
 * own bitmap font travels with it, keeps working if Mojang rearranges the tab
 * layout, and needs no coordinate maths.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void leicraftclient$markLeiCraftPlayers(
		final PlayerInfo info,
		final CallbackInfoReturnable<Component> cir
	) {
		final Component name = cir.getReturnValue();
		if (name == null) {
			return;
		}

		final String username = info.getProfile().name();
		if (username == null || !LeiCraftUsers.INSTANCE.isKnown(username)) {
			return;
		}

		cir.setReturnValue(Component.empty().append(TabListIcon.INSTANCE.badge()).append(name));
	}
}
