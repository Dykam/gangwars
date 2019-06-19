package nl.dykam.dev.gangwars

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin

import java.io.File
import java.io.IOException
import java.util.*

data class Gang(val name: String, val members: List<UUID>)

sealed class AddResult {
    object GangDoesNotExist: AddResult()
    data class MemberAlreadyInGang(val gang: Gang): AddResult()
    data class Success(val gang: Gang): AddResult()
}

sealed class RemoveResult {
    object MemberNotInGang: RemoveResult()
    data class Success(val gang: Gang): RemoveResult()
}

class GangRegistry(private val plugin: Plugin, private val autoSave: Boolean) : Iterable<Gang> {
    private val customConfig: YamlConfiguration
    private val gangs: MutableMap<String, List<UUID>> = HashMap()

    private val memberToGang = HashMap<UUID, String>()

    private val customConfigFile: File

    init {
        customConfigFile = File(plugin.dataFolder, GANGS_YML)
        if (!customConfigFile.exists()) {
            customConfigFile.parentFile.mkdirs()
            customConfigFile.createNewFile()
        }

        customConfig = YamlConfiguration()
        load()
    }

    fun load() {
        try {
            gangs.clear()
            memberToGang.clear()
            customConfig.load(customConfigFile)
            customConfig.getKeys(false).forEach { gangName ->
                val members = customConfig.getStringList(gangName).map(UUID::fromString)
                gangs[gangName] = members
                members.forEach { member -> memberToGang[member] = gangName }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: InvalidConfigurationException) {
            e.printStackTrace()
        }
    }

    fun save() {
        customConfig.getKeys(false).forEach { gangName ->
            customConfig[gangName] = null
        }

        gangs.forEach { (gangName, members) ->
            customConfig.set(gangName, members.map { it.toString() })
        }
        customConfig.save(customConfigFile)
    }

    fun saveIfAutoSave() { if (autoSave) { save(); }}

    override fun iterator(): Iterator<Gang> {
        return gangs.asSequence().map { (key, value) -> Gang(key, value) }.iterator()
    }

    operator fun get(gang: String): Gang? {
        return gangs[gang]?.let { members -> Gang(gang, members) }
    }

    fun getForPlayer(member: UUID): Gang? {
        return memberToGang[member]?.let { gangName -> this[gangName] }
    }

    fun addMember(gangName: String, member: UUID): AddResult {
        memberToGang[member]?.let {
            return@addMember AddResult.MemberAlreadyInGang(Gang(it, gangs[it]!!))
        }

        return when (val gang = this[gangName]) {
            null -> AddResult.GangDoesNotExist
            else -> {
                gangs[gangName] = gang.members + member
                memberToGang[member] = gangName
                saveIfAutoSave()
                AddResult.Success(Gang(gangName, gangs[gangName]!!))
            }
        }
    }

    fun removeMember(member: UUID): RemoveResult {
        return when (val gangName = memberToGang[member]) {
            null -> RemoveResult.MemberNotInGang
            else -> {
                val gangMembers = gangs[gangName]!!
                gangs[gangName] = gangMembers - member
                memberToGang -= member
                saveIfAutoSave()
                RemoveResult.Success(Gang(gangName, gangs[gangName]!!))
            }
        }
    }

    fun createGang(gangName: String): Option<Gang> {
        if (gangs.containsKey(gangName)) {
            return None
        }
        val memberList = listOf<UUID>()
        val gang = Gang(gangName, memberList)
        gangs += Pair(gangName, memberList)
        saveIfAutoSave()
        return Some(gang)
    }

    fun removeGang(gangName: String): Gang? {
        val result = gangs.remove(gangName)
        saveIfAutoSave()
        return result?.let { Gang(gangName, it) }
    }

//    fun put(gang: Gang) {
//        when (val existing = this[gang.name]) {
//            is Some -> existing.t.members
//                    .filter { member -> !gang.members.contains(member) }
//                    .forEach { member -> memberToGang.remove(member) }
//        }
//
//        gang.members.forEach { member -> memberToGang[member] = gang.name }
//        gangs[gang.name] = gang.members
//    }

    companion object {
        const val GANGS_YML = "gangs.yml"
    }
}