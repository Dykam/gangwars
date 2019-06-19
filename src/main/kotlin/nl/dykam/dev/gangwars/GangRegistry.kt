package nl.dykam.dev.gangwars

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.configuration.serialization.ConfigurationSerializable
import org.bukkit.plugin.Plugin

import java.io.File
import java.io.IOException
import java.util.*

data class Gang(val name: String, val members: List<UUID>, val powerLevel: Float)
data class StorageGang(val members: List<String>, val powerLevel: Float) : ConfigurationSerializable {
    override fun serialize(): MutableMap<String, Any> = mutableMapOf(
        "members" to members,
        "powerLevel" to powerLevel
    )
    companion object {
        @JvmStatic
        fun deserialize(data: Map<String, Object>): StorageGang = StorageGang(
            data["members"] as List<String>,
            data["powerLevel"] as Float
        )
    }
}

sealed class AddResult {
    object GangDoesNotExist: AddResult()
    data class MemberAlreadyInGang(val gang: Gang): AddResult()
    data class Success(val gang: Gang): AddResult()
}

sealed class RemoveResult {
    object MemberNotInGang: RemoveResult()
    data class Success(val gang: Gang): RemoveResult()
}

class GangRegistry(plugin: Plugin, private val autoSave: Boolean) : Iterable<Gang> {
    private val customConfig: YamlConfiguration
    private val gangs: MutableMap<String, Gang> = HashMap()

    private val memberToGang = HashMap<UUID, String>()

    private val newGangs: AutoDeriveSet<Gang> = AutoDeriveSet()
    private val newMembers = newGangs.add(createMultiKeyDerivative<UUID, Gang> { gang -> gang.members })
    private val newGangName = newGangs.add(createKeyDerivative<String, Gang> { gang -> gang.name })

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
            newGangs.clear()
            gangs.clear()
            memberToGang.clear()
            customConfig.load(customConfigFile)
            customConfig.getKeys(false).forEach { gangName ->
                val storageGang = customConfig.getSerializable(gangName, StorageGang::class.java)
                val gang = Gang(gangName, storageGang.members.map(UUID::fromString), storageGang.powerLevel)

                gangs[gangName] = gang
                gang.members.forEach { member -> memberToGang[member] = gangName }
                newGangs += gang
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

        gangs.forEach { (_, gang) ->
            var storageGang = StorageGang(
                gang.members.map { it.toString() },
                gang.powerLevel
            )
            customConfig.set(gang.name, storageGang.serialize())
        }
        customConfig.save(customConfigFile)
    }

    private fun saveIfAutoSave() { if (autoSave) { save(); }}

    override fun iterator(): Iterator<Gang> {
        return gangs.values.iterator()
    }

    operator fun get(gang: String): Gang? {
        return gangs[gang]
    }

    fun getForPlayer(member: UUID): Gang? {
        return memberToGang[member]?.let { gangName -> this[gangName] }
    }

    fun addMember(gangName: String, member: UUID): AddResult {
        memberToGang[member]?.let {
            return@addMember AddResult.MemberAlreadyInGang(gangs[it]!!)
        }

        return when (val gang = this[gangName]) {
            null -> AddResult.GangDoesNotExist
            else -> {
                val updatedGang = gang.copy(members = gang.members + member)
                newGangs -= gang
                newGangs += updatedGang
                gangs[gangName] = updatedGang
                memberToGang[member] = gangName
                saveIfAutoSave()
                AddResult.Success(updatedGang)
            }
        }
    }

    fun removeMember(member: UUID): RemoveResult {
        return when (val gangName = memberToGang[member]) {
            null -> RemoveResult.MemberNotInGang
            else -> {
                val gang = gangs[gangName]!!
                val updatedGang = gang.copy(members = gang.members - member)
                newGangs -= gang
                newGangs += updatedGang
                gangs[gangName] = updatedGang
                memberToGang -= member
                saveIfAutoSave()
                RemoveResult.Success(updatedGang)
            }
        }
    }

    fun createGang(gangName: String): Option<Gang> {
        if (gangs.containsKey(gangName)) {
            return None
        }
        val memberList = listOf<UUID>()
        val gang = Gang(gangName, memberList, 50f)
        newGangs += gang
        gangs += Pair(gangName, gang)
        saveIfAutoSave()
        return Some(gang)
    }

    fun removeGang(gangName: String): Gang? {
        val gang = gangs.remove(gangName)
        gang?.let { newGangs -= it }
        gang?.members?.forEach { memberToGang -= it }
        saveIfAutoSave()
        return gang
    }

    companion object {
        const val GANGS_YML = "gangs.yml"
    }
}