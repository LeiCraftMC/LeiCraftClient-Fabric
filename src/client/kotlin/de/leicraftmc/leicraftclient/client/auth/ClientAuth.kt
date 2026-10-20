package de.leicraftmc.leicraftclient.client.auth

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.exceptions.AuthenticationException
import com.mojang.authlib.exceptions.AuthenticationUnavailableException
import com.mojang.authlib.exceptions.InvalidCredentialsException
import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.api.ApiModels
import de.leicraftmc.leicraftclient.client.api.ApiResult
import de.leicraftmc.leicraftclient.client.api.LeiCraftApi
import de.leicraftmc.leicraftclient.client.config.ClientConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Signs the mod in to the API with the game's Minecraft session.
 *
 * The handshake proves account ownership the way a Minecraft server checks a
 * joining player, so the access token never leaves the game:
 *
 * 1. `POST /v1/client/auth/challenge` hands out a one-time `server_id`.
 * 2. The game calls Mojang's `joinServer(profileId, accessToken, serverId)`
 *    through authlib's `SessionService` (`Minecraft.services().sessionService()`).
 * 3. `POST /v1/client/auth/verify` asks Mojang whether that join happened and
 *    answers with a client token (valid 7 days).
 *
 * The token is kept in `config/leicraftclient-session.json` and reused across
 * restarts while it is valid for the same account. Offline and dev accounts
 * cannot do step 2, so they stay [State.Offline]; nothing here ever throws into
 * the game — failures end in [State.Failed] and are logged.
 */
object ClientAuth {

    /** How long before its expiry a stored token is replaced instead of reused. */
    private const val EXPIRY_MARGIN_MS = 60_000L

    /** A failed handshake is not retried automatically more often than this. */
    private const val RETRY_INTERVAL_MS = 15_000L

    sealed interface State {
        /** `accountServicesEnabled` is off; only public profiles are read. */
        data object Disabled : State

        /** An offline or dev account, which Mojang cannot vouch for. */
        data object Offline : State

        data object Connecting : State

        data class Connected(
            val account: ApiModels.MinecraftAccount,
            /** The LeiCraft user this Minecraft account is linked to, if any. */
            val linkedUser: ApiModels.PublicUser?,
        ) : State

        data class Failed(val reason: String) : State
    }

    /** What `config/leicraftclient-session.json` holds. */
    data class StoredSession(val token: String, val expiresAt: Long, val uuid: UUID) {

        /** Reusable for [account] at [now]: same account, and not about to expire. */
        fun isUsableFor(account: UUID, now: Long): Boolean =
            uuid == account && expiresAt - now > EXPIRY_MARGIN_MS

        fun toJson(): String = GsonBuilder().setPrettyPrinting().create().toJson(
            JsonObject().apply {
                addProperty("token", token)
                addProperty("expires_at", expiresAt)
                addProperty("uuid", uuid.toString())
            },
        )

        companion object {
            /** Null for a missing, partial or corrupt file. */
            fun fromJson(json: String): StoredSession? = runCatching {
                val o = JsonParser.parseString(json).asJsonObject
                StoredSession(
                    token = o.get("token").asString.takeIf { it.isNotBlank() } ?: return null,
                    expiresAt = o.get("expires_at").asLong,
                    uuid = UUID.fromString(o.get("uuid").asString),
                )
            }.getOrNull()
        }
    }

    /**
     * Whether the game runs with a real Microsoft account that can join servers.
     *
     * Offline and dev launches get a name-based version 3 UUID
     * (`UUIDUtil.createOfflinePlayerUUID`) and a placeholder token; real
     * accounts always have a random version 4 UUID.
     */
    fun isOnlineAccount(profileId: UUID, accessToken: String?, offlineDeveloperMode: Boolean): Boolean {
        if (offlineDeveloperMode) return false
        if (profileId.version() != 4) return false
        val token = accessToken?.trim().orEmpty()
        return token.isNotEmpty() && token != "0" && !token.equals("FabricMC", ignoreCase = true)
    }

    private val mutableState = MutableStateFlow<State>(State.Disabled)

    /** The current sign-in state; screens read `.value`, coroutines may collect it. */
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var session: StoredSession? = null

    @Volatile
    private var lastFailureAt: Long = 0

    private val sessionFile: Path
        get() = FabricLoader.getInstance().configDir.resolve("${LeiCraftClient.MOD_ID}-session.json")

    /** Signs in in the background; called once the client has started, and when re-enabled. */
    fun start() {
        LeiCraftClient.scope.launch { connect(force = true) }
    }

    /** Switches account services off. The stored token is kept for when they come back. */
    fun disable() {
        mutableState.value = State.Disabled
    }

    /** The linked LeiCraft user of the current account, if signed in and linked. */
    fun linkedUser(): ApiModels.PublicUser? = (state.value as? State.Connected)?.linkedUser

    /** Records a link change (after linking or unlinking) without another round trip. */
    fun updateLinkedUser(user: ApiModels.PublicUser?) {
        mutableState.value = when (val current = state.value) {
            is State.Connected -> current.copy(
                linkedUser = user,
                account = current.account.copy(linkedAt = if (user == null) null else current.account.linkedAt ?: System.currentTimeMillis()),
            )
            else -> current
        }
    }

    /**
     * A usable client token, signing in first if needed. Errors explain why
     * there is none (disabled, offline account, handshake failed).
     */
    suspend fun token(): ApiResult<String> {
        session?.let { stored ->
            if (state.value is State.Connected) return ApiResult.Ok(stored.token, "")
        }
        val outcome = connect(force = false)
        val stored = session
        return when {
            outcome is State.Connected && stored != null -> ApiResult.Ok(stored.token, "")
            outcome is State.Disabled -> ApiResult.Error(ApiResult.NOT_SIGNED_IN, "Account services are disabled")
            outcome is State.Offline -> ApiResult.Error(ApiResult.NOT_SIGNED_IN, "Offline account")
            outcome is State.Failed -> ApiResult.Error(ApiResult.NOT_SIGNED_IN, outcome.reason)
            else -> ApiResult.Error(ApiResult.NOT_SIGNED_IN, "Not signed in")
        }
    }

    /**
     * Called when [staleToken] was rejected with `401`. Runs the handshake once
     * (unless another caller already replaced the token) and returns the new
     * token, or null if signing in failed.
     */
    suspend fun refreshAfterUnauthorized(staleToken: String): String? = lock.withLock {
        val current = session
        if (current != null && current.token != staleToken && state.value is State.Connected) {
            return@withLock current.token
        }
        forgetSession()
        val outcome = signInLocked(allowStored = false)
        if (outcome is State.Connected) session?.token else null
    }

    /**
     * Brings the state up to date: [State.Disabled] / [State.Offline] when no
     * handshake is possible, otherwise reuses or replaces the session.
     *
     * Without [force], an already connected session is kept, and a recent
     * failure is reported again instead of hammering Mojang and the API.
     */
    suspend fun connect(force: Boolean): State = lock.withLock {
        if (!force) {
            val current = state.value
            if (current is State.Connected && session != null) return@withLock current
            if (current is State.Failed && System.currentTimeMillis() - lastFailureAt < RETRY_INTERVAL_MS) {
                return@withLock current
            }
        }
        signInLocked(allowStored = true)
    }

    /** Must hold [lock]. Never calls the client endpoints that need [token]. */
    private suspend fun signInLocked(allowStored: Boolean): State {
        if (!ClientConfig.accountServicesEnabled) return publish(State.Disabled)

        val minecraft = Minecraft.getInstance()
        val user = minecraft.user
        if (!isOnlineAccount(user.profileId, user.accessToken, minecraft.isOfflineDeveloperMode)) {
            return publish(State.Offline)
        }

        publish(State.Connecting)

        if (allowStored) {
            val stored = session ?: readStoredSession()
            if (stored != null && stored.isUsableFor(user.profileId, System.currentTimeMillis())) {
                when (val result = LeiCraftApi.session(stored.token)) {
                    is ApiResult.Ok -> {
                        session = stored
                        LeiCraftClient.LOGGER.info("Signed in to LeiCraft as {} (stored session)", result.data.account.name)
                        return publish(State.Connected(result.data.account, result.data.user))
                    }
                    is ApiResult.Error -> if (!result.isUnauthorized) {
                        // The API is unreachable rather than the token bad: keep it
                        // for the next attempt instead of burning a handshake.
                        session = stored
                        return fail("API unreachable: ${result.message}")
                    }
                }
            }
        }

        return handshake(minecraft)
    }

    private suspend fun handshake(minecraft: Minecraft): State {
        val user = minecraft.user

        val challenge = when (val result = LeiCraftApi.challenge()) {
            is ApiResult.Ok -> result.data
            is ApiResult.Error -> return fail("Challenge failed: ${result.message}")
        }

        try {
            withContext(Dispatchers.IO) {
                minecraft.services().sessionService().joinServer(user.profileId, user.accessToken, challenge.serverId)
            }
        } catch (e: AuthenticationUnavailableException) {
            return fail("Mojang session servers are unavailable")
        } catch (e: InvalidCredentialsException) {
            return fail("Mojang rejected the session (restart the launcher)")
        } catch (e: AuthenticationException) {
            return fail("Mojang: ${e.message ?: e.javaClass.simpleName}")
        }

        val verified = when (val result = LeiCraftApi.verify(challenge.challengeId, user.name)) {
            is ApiResult.Ok -> result.data
            is ApiResult.Error -> return fail("Verification failed: ${result.message}")
        }

        val stored = StoredSession(verified.token, verified.expiresAt, verified.account.uuid)
        session = stored
        writeStoredSession(stored)
        LeiCraftClient.LOGGER.info("Signed in to LeiCraft as {}", verified.account.name)
        return publish(State.Connected(verified.account, verified.user))
    }

    private fun fail(reason: String): State {
        lastFailureAt = System.currentTimeMillis()
        LeiCraftClient.LOGGER.warn("LeiCraft sign-in failed: {}", reason)
        return publish(State.Failed(reason))
    }

    private fun publish(next: State): State {
        // A late result must not undo the user switching account services off.
        if (!ClientConfig.accountServicesEnabled && next !is State.Disabled) {
            mutableState.value = State.Disabled
            return State.Disabled
        }
        mutableState.value = next
        return next
    }

    private fun forgetSession() {
        session = null
        runCatching { Files.deleteIfExists(sessionFile) }
    }

    private fun readStoredSession(): StoredSession? {
        val file = sessionFile
        if (!Files.exists(file)) return null
        return runCatching { StoredSession.fromJson(Files.readString(file)) }.getOrNull()
    }

    private fun writeStoredSession(stored: StoredSession) {
        try {
            Files.createDirectories(sessionFile.parent)
            Files.writeString(sessionFile, stored.toJson())
        } catch (e: Exception) {
            LeiCraftClient.LOGGER.warn("Could not write {}", sessionFile, e)
        }
    }
}
