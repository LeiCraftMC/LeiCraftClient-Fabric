package de.leicraftmc.leicraftclient.mixin.client;

import com.mojang.authlib.GameProfile;
import de.leicraftmc.leicraftclient.client.profiles.PlayerProfiles;
import java.util.Optional;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps a LeiCraft cape into the player's skin.
 *
 * Patching the skin rather than cloning {@code CapeLayer} keeps the cape subject
 * to everything vanilla does with it -- the cape toggle in skin customisation,
 * elytra swapping, chest-equipment offsets -- so there is nothing of vanilla's
 * cape handling to keep in sync on a Minecraft update. Elytra without their own
 * texture fall back to the cape, as in vanilla.
 *
 * The cape is looked up by the player's UUID ({@link PlayerProfiles}), which is a
 * map read: this runs once per player per frame.
 *
 * Mixins must be Java: the Mixin processor does not handle Kotlin classes
 * reliably, so this delegates straight into Kotlin.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void leicraftclient$applyCustomCape(final CallbackInfoReturnable<PlayerSkin> cir) {
		final PlayerSkin skin = cir.getReturnValue();
		if (skin == null) {
			return;
		}

		final GameProfile profile = ((AbstractClientPlayer) (Object) this).getGameProfile();
		final Identifier cape = PlayerProfiles.INSTANCE.capeFor(profile.id(), profile.name());
		if (cape == null) {
			return;
		}

		// Both components must be passed explicitly: the single-argument
		// constructor treats its argument as an asset id and rewrites the path to
		// "textures/<id>.png", which would miss the texture we registered.
		cir.setReturnValue(skin.with(PlayerSkin.Patch.create(
			Optional.empty(),
			Optional.of(new ClientAsset.ResourceTexture(cape, cape)),
			Optional.empty(),
			Optional.empty()
		)));
	}
}
