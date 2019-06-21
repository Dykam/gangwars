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
        fun deserialize(data: Map<String, Any>): StorageGang = StorageGang(
            data["members"] as List<String>,
            (data["powerLevel"] as Double).toFloat()
        )
    }
}

sealed class AddResult {
    object GangDoesNotExist: AddResult()
    data class MemberAlreadyInGang(val gang: Gang): AddResult()
    data class Success(val gang: Gang): AddResult()
    object Unknown: AddResult()
}

sealed class RemoveResult {
    object MemberNotInGang: RemoveResult()
    data class Success(val gang: Gang): RemoveResult()
    object Unknown: RemoveResult()
}

class GangRegistry(plugin: Plugin, private val autoSave: Boolean) : Iterable<Gang> {
    private val customConfig: YamlConfiguration

    private val set: AutoDerive<Gang> = AutoDerive()
    private val gangs = set.add(createSetDerivative())

    private val byMember = set.add(createMultiKeyDerivative<UUID, Gang> { gang -> gang.members })
    private val byName = set.add(createKeyDerivative<String, Gang> { gang -> gang.name })

    private val customConfigFile: File

    init {
        customConfigFile = File(plugin.dataFolder, GANGS_YML)
        if (!customConfigFile.exists()) {
            customConfigFile.parentFile.mkdirs()
            customConfigFile.createNewFile()
        }

        customConfig = YamlConfiguration()
    }

    fun load() {
        try {
            set.clear()
            customConfig.load(customConfigFile)
            customConfig.getKeys(false).forEach { gangName ->
                val storageGang = customConfig.getSerializable(gangName, StorageGang::class.java)
                val gang = Gang(gangName, storageGang.members.map(UUID::fromString), storageGang.powerLevel)
                set += gang
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

        gangs.forEach { gang ->
            customConfig.set(gang.name, StorageGang(
                gang.members.map { it.toString() },
                gang.powerLevel
            ))
        }
        customConfig.save(customConfigFile)
    }

    private fun saveIfAutoSave() { if (autoSave) { save(); }}

    override fun iterator(): Iterator<Gang> {
        return gangs.iterator()
    }

    operator fun get(gang: String): Gang? {
        return byName[gang]
    }

    fun getForPlayer(member: UUID): Gang? {
        return byMember[member]
    }

    fun addMember(gangName: String, member: UUID): AddResult {
        byMember[member]?.let {
            return@addMember AddResult.MemberAlreadyInGang(it)
        }
        if (byName[gangName] == null) {
            return AddResult.GangDoesNotExist
        }

        val updatedGang = byName.update(gangName) { it.copy(members = it.members + member) }
        return if (updatedGang != null) {
            saveIfAutoSave()
            AddResult.Success(updatedGang)
        } else {
            AddResult.Unknown
        }
    }

    fun removeMember(member: UUID): RemoveResult {
        if (byMember[member] == null) {
            return RemoveResult.MemberNotInGang
        }
        val updatedGang = byMember.update(member) { it.copy(members = it.members - member) }
        return if (updatedGang != null) {
            saveIfAutoSave()
            RemoveResult.Success(updatedGang)
        } else {
            RemoveResult.Unknown
        }
    }

    fun createGang(gangName: String): Option<Gang> {
        if (byName.containsKey(gangName)) {
            return None
        }
        val memberList = listOf<UUID>()
        val gang = Gang(gangName, memberList, 0f)
        set += gang
        saveIfAutoSave()
        return Some(gang)
    }

    fun removeGang(gangName: String): Gang? {
        val gang = byName[gangName]
        gang?.let { set -= it }
        saveIfAutoSave()
        return gang
    }

    companion object {
        const val GANGS_YML = "gangs.yml"
    }
}