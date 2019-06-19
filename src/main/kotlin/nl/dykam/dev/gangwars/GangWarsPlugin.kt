package nl.dykam.dev.gangwars

import arrow.core.None
import arrow.core.Some
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.events.DisallowedPVPEvent
import com.sk89q.worldguard.protection.flags.DefaultFlag
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
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
    private lateinit var worldGuard: WorldGuardPlugin
    private val openInvites: MutableMap<Pair<String, UUID>, BukkitTask> = mutableMapOf()

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

    private val playerCommands: Map<String, CommandBase<Player>> = mapOf(
            "gang-join" to CommandBase.Command1 { player, gangName ->
                val gang = registry[gangName]
                if (gang == null) {
                    player.sendMessage(format.fail("Gang $gangName doesn't exist"))
                    return@Command1
                }

                val existingGang = registry.getForPlayer(player.uniqueId)
                if (existingGang != null) {
                    player.sendMessage(format.fail("You're already in ${existingGang.name}"))
                    return@Command1
                }

                val clearTask = openInvites.remove(Pair(gangName, player.uniqueId))
                if (clearTask == null) {
                    player.sendMessage(format.fail("You aren't invited to $gangName"))
                } else {
                    registry.addMember(gangName, player.uniqueId)
                    player.sendMessage(format.success("You have joined $gangName"))
                    gang.broadcast(format.success("${player.displayName.stripColor()} joined the gang"))

                    openInvites.asSequence()
                            .filter { (pair, _) -> pair.second == player.uniqueId }
                            .forEach { (pair, _) ->
                                openInvites.remove(pair)
                            }
                }
            },
            "gang-leave" to CommandBase.Command0 { player ->
                val gang = registry.getForPlayer(player.uniqueId)
                if (gang == null) {
                    player.sendMessage(format.fail("You're not in a gang"))
                    return@Command0
                }

                if (gang.members.count() == 1) {
                    player.sendMessage(format.fail("You're the last member, use /gang-disband instead"))
                    return@Command0
                }

                registry.removeMember(player.uniqueId)
                player.sendMessage(format.success("You left the gang"))
                registry[gang.name]?.broadcast(format.success("${player.displayName.stripColor()} left the gang"))
            },
            "gang-invite" to CommandBase.Command1 { player, memberName ->
                val gang = registry.getForPlayer(player.uniqueId)
                if (gang == null) {
                    player.sendMessage(format.fail("You're not in a gang"))
                    return@Command1
                }

                if (gang.members[0] != player.uniqueId) {
                    player.sendMessage(format.fail("You're not the leader of the gang"))
                    return@Command1
                }

                val uuid = memberName.let {
                    try {
                        UUID.fromString(it)
                    } catch (ex: IllegalArgumentException) {
                        val member = server.getPlayerExact(memberName)
                        if (member == null) {
                            player.sendMessage(format.fail("Player ${memberName.stripColor()} doesn't exist"))
                            return@Command1
                        }
                        member.uniqueId
                    }
                }

                val existingGang = registry.getForPlayer(uuid)
                if (existingGang != null) {
                    player.sendMessage(format.fail("Player ${memberName.stripColor()} is already in ${existingGang.name}"))
                    return@Command1
                }

                val pair = Pair(gang.name, uuid)
                if (pair in openInvites) {
                    player.sendMessage(format.fail("Player ${memberName.stripColor()} was already invited, extending invitation"))
                } else {
                    player.sendMessage(format.success("Player ${memberName.stripColor()} has been invited"))
                    server.getPlayer(uuid).sendMessage(format.success("You have been invited to ${gang.name} by ${player.displayName.stripColor()}"))
                }

                var task: BukkitTask? = null
                task = server.scheduler.runTaskLater(this, {
                    if (openInvites[pair] != task) {
                        return@runTaskLater
                    }
                    openInvites.remove(pair)
                    player.sendMessage("${ChatColor.COLOR_CHAR}Invite for player ${memberName.stripColor()} expired")
                }, INVITATION_DURATION)
                openInvites[pair] = task!!

            },
            "gang-invite-cancel" to CommandBase.Command1 { player, memberName ->
                val gang = registry.getForPlayer(player.uniqueId)
                if (gang == null) {
                    player.sendMessage(format.fail("You're not in a gang"))
                    return@Command1
                }

                if (gang.members[0] != player.uniqueId) {
                    player.sendMessage(format.fail("You're not the leader of the gang"))
                    return@Command1
                }

                val uuid = memberName.let {
                    try {
                        UUID.fromString(it)
                    } catch (ex: IllegalArgumentException) {
                        val member = server.getPlayerExact(memberName)
                        if (member == null) {
                            player.sendMessage(format.fail("Player ${memberName.stripColor()} doesn't exist"))
                            return@Command1
                        }
                        member.uniqueId
                    }
                }

                val clearTask = openInvites.remove(Pair(gang.name, uuid))
                if (clearTask == null) {
                    player.sendMessage(format.fail("Player ${memberName.stripColor()} wasn't invited"))
                    return@Command1
                }

                openInvites.remove(Pair(gang.name, uuid))
                player.sendMessage(format.success("Player ${memberName.stripColor()}'s invite has been cancelled"))
                server.getPlayer(uuid).sendMessage(format.success("Your invite for ${gang.name} has been cancelled by ${player.displayName.stripColor()}"))
            },
            "gang-kick" to CommandBase.Command1 { player, memberName ->
                val gang = registry.getForPlayer(player.uniqueId)
                if (gang == null) {
                    player.sendMessage(format.fail("You're not in a gang"))
                    return@Command1
                }

                if (gang.members[0] != player.uniqueId) {
                    player.sendMessage(format.fail("You're not the leader of the gang"))
                    return@Command1
                }

                val uuid = memberName.let {
                    try {
                        UUID.fromString(it)
                    } catch (ex: IllegalArgumentException) {
                        val member = server.getPlayerExact(memberName)
                        if (member == null) {
                            player.sendMessage(format.fail("Player ${memberName.stripColor()} doesn't exist"))
                            return@Command1
                        }
                        member.uniqueId
                    }
                }

                if (player.uniqueId == uuid) {
                    player.sendMessage(format.fail("You can't kick yourself"))
                    return@Command1
                }

                when(registry.removeMember(uuid)) {
                    is RemoveResult.MemberNotInGang -> {
                        player.sendMessage(format.fail("Player ${memberName.stripColor()} is not in the gang"))
                        return@Command1
                    }
                }

                registry[gang.name]?.broadcast(format.success("Player ${memberName.stripColor()} has been kicked from the gang"))
            },
            "gang-create" to CommandBase.Command1 { player, gangName ->
                val existingGang = registry.getForPlayer(player.uniqueId)
                if (existingGang != null) {
                    player.sendMessage(format.fail("You're already in ${existingGang.name}"))
                    return@Command1
                }

                when (registry.createGang(gangName)) {
                    is Some -> {
                        registry.addMember(gangName, player.uniqueId)
                        player.sendMessage(format.success("Successfully created gang $gangName"))
                    }
                    is None -> player.sendMessage(format.fail("Gang $gangName already exists"))
                }
            },
            "gang-disband" to CommandBase.Command0 { player ->
                val gang = registry.getForPlayer(player.uniqueId)
                if (gang == null) {
                    player.sendMessage(format.fail("You're not in a gang"))
                    return@Command0
                }
                val firstMember = gang.members.first()
                if (firstMember != player.uniqueId) {
                    player.sendMessage(format.fail("You're not the owner of this gang, ${server.getPlayer(firstMember).displayName.stripColor()} is"))
                    return@Command0
                }

                registry.removeGang(gang.name);
                player.sendMessage(format.success("Successfully removed gang ${gang.name}"))
                gang.broadcast("Your gang ${gang.name} was disbanded by ${player.displayName.stripColor()}")
            }
    )
    private val serverCommands: Map<String, CommandBase<CommandSender>> = mapOf(
            "gang-info" to CommandBase.Command1 { sender, gangName ->
                val gang = registry[gangName]
                if (gang == null) {
                    sender.sendMessage(format.fail("This gang doesn't exist"))
                    return@Command1
                }

                when (gang.members.count()) {
                    0 -> sender.sendMessage(format.notice("Gang $gangName: No members"))
                    else -> {
                        val members = gang.members.asSequence().map { getPlayerName(it).stripColor() }.toMutableList()
                        members[0] += " (Leader)"
                        sender.sendMessage(format.notice("Gang $gangName: ${members.joinToStringAnd()}"))
                    }
                }
            },
            "gang-list" to CommandBase.Command0 { sender ->
                val gangs = registry.toList()
                if (gangs.count() == 0) {
                    sender.sendMessage(format.notice("There are no gangs"))
                } else {
                    sender.sendMessage(format.notice("Gangs: ${gangs.joinToStringAnd {gang -> gang.name}}"))
                }
            },
            "gang-admin-join" to CommandBase.Command2 { sender, gangName, memberName ->
                val gang = registry[gangName]
                if (gang == null) {
                    sender.sendMessage(format.fail("Gang $gangName doesn't exist"))
                    return@Command2
                }

                val uuid = memberName.let {
                    try {
                        UUID.fromString(it)
                    } catch (ex: IllegalArgumentException) {
                        val member = server.getPlayerExact(memberName)
                        if (member == null) {
                            sender.sendMessage(format.fail("Player $memberName doesn't exist"))
                            return@Command2
                        }
                        member.uniqueId
                    }
                }
                when(val result = registry.addMember(gangName, uuid)) {
                    is AddResult.MemberAlreadyInGang -> {
                        sender.sendMessage(format.fail("Player is already in ${result.gang.name}"))
                    }
                    is AddResult.GangDoesNotExist -> {
                        sender.sendMessage(format.fail("Gang $gangName does not exist"))
                    }
                    is AddResult.Success -> {
                        sender.sendMessage(format.fail("Player ${memberName.stripColor()} added to $gangName"))
                    }
                }
            },
            "gang-admin-kick" to CommandBase.Command2 { sender, gangName, memberName ->
                val gang = registry[gangName]
                if (gang == null) {
                    sender.sendMessage(format.fail("Gang $gangName doesn't exist"))
                    return@Command2
                }

                val uuid = memberName.let {
                    try {
                        UUID.fromString(it)
                    } catch (ex: Exception) {
                        val member = server.getPlayerExact(memberName)
                        if (member == null) {
                            sender.sendMessage(format.fail("Player ${memberName.stripColor()} doesn't exist"))
                            return@Command2
                        }
                        member.uniqueId
                    }
                }
                registry.removeMember(uuid)
            },
            "gang-admin-disband" to CommandBase.Command1 { sender, gangName ->
                val gang = registry.removeGang(gangName)
                if (gang == null) {
                    sender.sendMessage(format.fail("Gang $gangName doesn't exist"))
                    return@Command1
                }

                sender.sendMessage(format.success("Successfully removed gang $gangName"))

                gang.broadcast("Your gang $gangName was disbanded")
            },
            "gang-admin-create" to CommandBase.Command1 { sender, gangName ->
                when (registry.createGang(gangName)) {
                    is Some -> sender.sendMessage(format.success("Successfully created gang $gangName"))
                }
            },
            "gang-admin-reload" to CommandBase.Command0 {
                registry.load()
            }
    )

    override fun onEnable() {
        super.onEnable()
        config.options().copyDefaults(true)

        Bukkit.getPluginManager().registerEvents(this, this)

        parseConfig()
        saveConfig()

        registry = GangRegistry(this, true)
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
        val config = config
    }

    companion object {

        val GANG_WARS = StateFlag("gangwars", false)
        val INVITATION_DURATION = 20L * 60
    }
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
    return Bukkit.getServer().getPlayer(uuid)?.displayName ?: Bukkit.getServer().getOfflinePlayer(uuid).name
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