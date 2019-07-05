package nl.dykam.dev.gangwars

import arrow.core.None
import arrow.core.Some
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.events.DisallowedPVPEvent
import com.sk89q.worldguard.protection.flags.DefaultFlag
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.flags.StringFlag
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.configuration.serialization.ConfigurationSerializable
import org.bukkit.configuration.serialization.ConfigurationSerialization
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.StringReader
import java.util.*

sealed class CommandBase<T : CommandSender>(val count: Int) {
    data class Command0<T : CommandSender>(val runner: (sender: T) -> Unit): CommandBase<T>(0) {
        override fun callCommand(sender: T, args: Array<String>) { runner(sender) }
    }
    data class Command1<T : CommandSender>(val runner: (sender: T, arg1: String) -> Unit): CommandBase<T>(1) {
        override fun callCommand(sender: T, args: Array<String>) { runner(sender, args[0]) }
    }
    data class Command2<T : CommandSender>(val runner: (sender: T, arg1: String, arg2: String) -> Unit): CommandBase<T>(2) {
        override fun callCommand(sender: T, args: Array<String>) { runner(sender, args[0], args[1]) }
    }
    data class Command3<T : CommandSender>(val runner: (sender: T, arg1: String, arg2: String, arg3: String) -> Unit): CommandBase<T>(3) {
        override fun callCommand(sender: T, args: Array<String>) { runner(sender, args[0], args[1], args[2]) }
    }
    protected abstract fun callCommand(sender: T, args: Array<String>)

    operator fun invoke(sender: T, args: Array<String>): Boolean {
        if (args.count() != count) {
            return false
        }

        callCommand(sender, args)

        return true
    }
}

class GangWarsPlugin : JavaPlugin(), Listener {
    private lateinit var configuration: Config
    private lateinit var worldGuard: WorldGuardPlugin

    override fun onLoad() {
        super.onLoad()

        worldGuard = WorldGuardPlugin.inst()
        val flagRegistry = worldGuard.flagRegistry
        try {
            flagRegistry.register(GANG_WARS)
        } catch (ex: FlagConflictException) {
            this.server.consoleSender.sendMessage(format.fail("Flag for "))
            this.pluginLoader.disablePlugin(this)
        }
    }

    private lateinit var registry: GangRegistry
    private lateinit var playerCommands: Map<String, CommandBase<Player>>
    private lateinit var serverCommands: Map<String, CommandBase<CommandSender>>

    override fun onEnable() {
        super.onEnable()

        config.options().copyDefaults(true)

        Bukkit.getPluginManager().registerEvents(this, this)

        parseConfig()
        saveConfig()

        registry = GangRegistry(this, true).also {
            try {
                it.load()
            } catch (ex: Exception) {
                // TODO: Handle. Copy failed config and clear, or disable
                server.consoleSender.sendMessage("Gangs failed to load")
            }
        }

        val (p, s) = getCommands(server, registry)
        playerCommands = p
        serverCommands = s
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        when (sender) {
            is Player -> {
                val playerCommand = playerCommands[label]
                if (playerCommand != null) {
                    return playerCommand(sender, args)
                }
            }
        }
        val serverCommand = serverCommands[label]
        if (serverCommand != null) {
            return serverCommand(sender, args)
        }
        return false
    }

    @EventHandler
    fun onDisallowedPVP(disallowedPVPEvent: DisallowedPVPEvent) {
        val attackerIsInNoPVP = playerIsInNoPVP(disallowedPVPEvent.attacker)
        val attackerIsInGangWars = playerIsInGangWars(disallowedPVPEvent.attacker)
        val defenderIsInNoPVP = playerIsInNoPVP(disallowedPVPEvent.defender)
        val defenderIsInGangWars = playerIsInGangWars(disallowedPVPEvent.defender)
        val isInGangWarsZone = attackerIsInNoPVP && attackerIsInGangWars || defenderIsInNoPVP && defenderIsInGangWars

        if (!isInGangWarsZone) {
            return
        }

        val attackerGang = registry.getForPlayer(disallowedPVPEvent.attacker.uniqueId)
        val defenderGang = registry.getForPlayer(disallowedPVPEvent.defender.uniqueId)

        if (attackerGang == null || defenderGang == null) {
            return
        }

        disallowedPVPEvent.isCancelled = true
    }

    private fun playerIsInNoPVP(player: Player): Boolean {
        val applicableRegions = worldGuard.getRegionManager(player.world).getApplicableRegions(player.location)
        return applicableRegions.queryState(worldGuard.wrapPlayer(player), DefaultFlag.PVP) == StateFlag.State.DENY
    }

    private fun playerIsInGangWars(player: Player): Boolean {
        val applicableRegions = worldGuard.getRegionManager(player.world).getApplicableRegions(player.location)
        val queryState = applicableRegions.queryState(worldGuard.wrapPlayer(player), GANG_WARS)
        return queryState == StateFlag.State.ALLOW
    }

    override fun onDisable() {}

    private fun parseConfig() {
        this.configuration = Config.deserialize(config.toMap())
    }

    companion object {
        val GANG_WARS = StateFlag("gangwars", false)
        val GANG_ZONE = StringFlag("gangzone")
        val INVITATION_DURATION = 20L * 60
    }
}

fun Server.getPlayerByNameOrUuid(nameOrUuid: String): OfflinePlayer? {
    return this.getPlayer(nameOrUuid) ?: try { this.getPlayer(UUID.fromString(nameOrUuid)) } catch (ex: IllegalArgumentException) { null }
}

fun String.stripColor(): String {
    return ChatColor.stripColor(this)
}
fun <T> List<T>.joinToStringAnd(transform: ((T) -> CharSequence)? = null): CharSequence {
    val fallback: ((T) -> CharSequence) = transform ?: { obj -> "$obj" }
    return when (this.count()) {
        0 -> ""
        1 -> "${fallback(this[0])}"
        else -> this.subList(0, this.count() - 1).joinToString(transform = transform) + " and " + fallback(this[this.count() - 1])
    }
}
fun Gang.broadcast(message: String) {
    members.forEach { memberId ->
        val member = Bukkit.getServer().getPlayer(memberId)
        member?.sendMessage(message)
    }
}
fun getPlayerName(uuid: UUID): String {
    return (Bukkit.getServer().getPlayer(uuid) ?: Bukkit.getServer().getOfflinePlayer(uuid)).getPlayerName()
}
fun OfflinePlayer.getPlayerName(): String {
    return if (this is Player) { this.displayName } else { this.name }
}

object format {
    fun success(message: String): String {
        return message("$message", ChatColor.GREEN)
    }
    fun fail(message: String): String {
        return message("$message", ChatColor.RED)
    }
    fun notice(message: String): String {
        return message("$message", ChatColor.GOLD)
    }
    fun message(message: String, color: ChatColor = ChatColor.RESET): String {
        return "${ChatColor.BLUE}[GANGWARS] $color$message"
    }
}