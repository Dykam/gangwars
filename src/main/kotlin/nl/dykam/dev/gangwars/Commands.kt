package nl.dykam.dev.gangwars

import arrow.core.None
import arrow.core.Some
import org.bukkit.ChatColor
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.*

fun Plugin.getCommands(server: Server, registry: GangRegistry): Pair<Map<String, CommandBase<Player>>, Map<String, CommandBase<CommandSender>>> {
    val openInvites: MutableMap<Pair<String, UUID>, BukkitTask> = mutableMapOf()
    val playerCommands: Map<String, CommandBase<Player>> = mapOf(
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

                val member = server.getPlayerByNameOrUuid(memberName)
                if (member == null) {
                    player.sendMessage(format.fail("Player ${memberName.stripColor()} doesn't exist"))
                    return@Command1
                }

                if (member.uniqueId == player.uniqueId) {
                    player.sendMessage(format.fail("You can't invite yourself"))
                    return@Command1
                }

                val existingGang = registry.getForPlayer(member.uniqueId)
                if (existingGang != null) {
                    player.sendMessage(format.fail("Player ${member.getPlayerName().stripColor()} is already in ${existingGang.name}"))
                    return@Command1
                }

                val pair = Pair(gang.name, member.uniqueId)
                if (pair in openInvites) {
                    player.sendMessage(format.fail("Player ${member.getPlayerName().stripColor()} was already invited, extending invitation"))
                } else {
                    player.sendMessage(format.success("Player ${member.getPlayerName().stripColor()} has been invited"))
                    if (member is Player) { member.sendMessage(format.success("You have been invited to ${gang.name} by ${player.displayName.stripColor()}")) }
                }

                var task: BukkitTask? = null
                task = server.scheduler.runTaskLater(this, {
                    if (openInvites[pair] != task) {
                        return@runTaskLater
                    }
                    openInvites.remove(pair)
                    player.sendMessage("${ChatColor.COLOR_CHAR}Invite for player ${member.getPlayerName().stripColor()} expired")
                }, GangWarsPlugin.INVITATION_DURATION)
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

                val member = server.getPlayerByNameOrUuid(memberName)
                if (member == null) {
                    player.sendMessage(format.fail("Player ${memberName.stripColor()} doesn't exist"))
                    return@Command1
                }

                val clearTask = openInvites.remove(Pair(gang.name, member.uniqueId))
                if (clearTask == null) {
                    player.sendMessage(format.fail("Player ${member.getPlayerName().stripColor()} wasn't invited"))
                    return@Command1
                }

                player.sendMessage(format.success("Player ${memberName.stripColor()}'s invite has been cancelled"))
                if (member is Player) member.sendMessage(format.success("Your invite for ${gang.name} has been cancelled by ${player.displayName.stripColor()}"))
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

                val member = server.getPlayerByNameOrUuid(memberName)
                if (member == null) {
                    player.sendMessage(format.fail("Player ${memberName.stripColor()} doesn't exist"))
                    return@Command1
                }

                if (player.uniqueId == member.uniqueId) {
                    player.sendMessage(format.fail("You can't kick yourself"))
                    return@Command1
                }

                when(registry.removeMember(member.uniqueId)) {
                    is RemoveResult.MemberNotInGang -> {
                        player.sendMessage(format.fail("Player ${member.getPlayerName().stripColor()} is not in the gang"))
                        return@Command1
                    }
                }

                registry[gang.name]?.broadcast(format.success("Player ${member.getPlayerName().stripColor()} has been kicked from the gang"))
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
    val serverCommands: Map<String, CommandBase<CommandSender>> = mapOf(
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
            "gang-who" to CommandBase.Command1 { sender, memberName ->
                val member = server.getPlayerByNameOrUuid(memberName)
                if (member == null) {
                    sender.sendMessage(format.notice("Player ${memberName.stripColor()} doesn't exist"))
                    return@Command1
                }
                val gang = registry.getForPlayer(member.uniqueId)
                if (gang == null) {
                    sender.sendMessage(format.fail("Player ${memberName.stripColor()} is not in a gang"))
                    return@Command1
                }

                sender.sendMessage(format.notice("Player is in ${gang.name}"))
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

                val member = server.getPlayerByNameOrUuid(memberName)
                if (member == null) {
                    sender.sendMessage(format.fail("Player ${memberName.stripColor()} doesn't exist"))
                    return@Command2
                }
                when(val result = registry.addMember(gangName, member.uniqueId)) {
                    is AddResult.MemberAlreadyInGang -> {
                        sender.sendMessage(format.fail("Player is already in ${result.gang.name}"))
                    }
                    is AddResult.GangDoesNotExist -> {
                        sender.sendMessage(format.fail("Gang $gangName does not exist"))
                    }
                    is AddResult.Success -> {
                        sender.sendMessage(format.fail("Player ${member.getPlayerName().stripColor()} added to $gangName"))
                    }
                    is AddResult.Unknown -> {
                        sender.sendMessage(format.fail("Something went wrong when trying to add player ${member.getPlayerName().stripColor()} to $gangName"))
                    }
                }
            },
            "gang-admin-kick" to CommandBase.Command2 { sender, gangName, memberName ->
                val gang = registry[gangName]
                if (gang == null) {
                    sender.sendMessage(format.fail("Gang $gangName doesn't exist"))
                    return@Command2
                }

                val member = server.getPlayerByNameOrUuid(memberName)
                if (member == null) {
                    sender.sendMessage(format.fail("Player ${memberName.stripColor()} doesn't exist"))
                    return@Command2
                }
                registry.removeMember(member.uniqueId)
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

    return Pair(playerCommands, serverCommands)
}