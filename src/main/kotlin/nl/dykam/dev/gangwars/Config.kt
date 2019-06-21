package nl.dykam.dev.gangwars

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.serialization.ConfigurationSerializable

data class Config(val powerLevels: PowerLevels, val peaceAndWar: PeaceAndWar, val income: Income) : ConfigurationSerializable {
    override fun serialize(): MutableMap<String, Any> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    data class PowerLevels(val gainOnKill: GainOnKill, val loss: Loss) : ConfigurationSerializable {
        data class GainOnKill(val constant: Float, val fractionOfEnemy: Float) {
            companion object {
                @JvmStatic
                fun deserialize(config: Map<String, Any>): GainOnKill = GainOnKill(
                        (config["constant"] as? Double ?: 0.0).toFloat(),
                        (config["fraction-of-enemy"] as? Double ?: 0.0).toFloat()
                )
            }
        }

        data class Loss(val constant: Float, val fraction: Float, val each: Each, val bonusForNegative: Float) {
            enum class Each {
                RealHour, RealDay, RealWeek, InGameDay, InGameHour
            }

            companion object {
                @JvmStatic
                fun deserialize(config: Map<String, Any>): Loss = Loss(
                        (config["constant"] as? Double ?: 0.0).toFloat(),
                        (config["fraction"] as? Double ?: 0.5).toFloat(),
                        parseEach(config["each"] as? String) ?: Each.RealWeek,
                        (config["bonus-for-negative"] as? Double ?: 0.0).toFloat()
                )

                private fun parseEach(each: String?): Each? = when (each) {
                    "real-hour" -> Each.RealHour
                    "real-day" -> Each.RealDay
                    "real-week" -> Each.RealWeek
                    "in-game-day" -> Each.InGameDay
                    "in-game-hour" -> Each.InGameHour
                    else -> null
                }
            }
        }

        override fun serialize(): MutableMap<String, Any> {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }
        companion object {
            @JvmStatic
            fun deserialize(config: Map<String, Any>): PowerLevels = PowerLevels(
                    GainOnKill.deserialize(config["gain-on-kill"] as Map<String, Any>),
                    Loss.deserialize(config["loss"] as Map<String, Any>)
            )

        }
    }

    data class PeaceAndWar(val warTime: WarTime) {
        data class WarTime(val start: Moment, val end: Moment, val DisableLeaveJoinGang: Boolean) {
            abstract class Moment {
                enum class Name {
                    Day, Noon, Sunset, Night, Midnight, Sunrise
                }

                data class Named(val name: Name) : Moment()
                data class Time(val hour: Int, val second: Int) : Moment()
                data class Ticks(val ticks: Int) : Moment()
                companion object {
                    private val timePattern = """^(\d{2}):(\d{2})$""".toRegex()
                    fun deserialize(moment: String?): Moment? {
                        return moment?.let {
                            parseName(it) ?: parseTime(it) ?: parseTicks(it)
                        }
                    }

                    private fun parseName(name: String): Named? = when (name) {
                        "day" -> Name.Day
                        "noon" -> Name.Noon
                        "sunset" -> Name.Sunset
                        "night" -> Name.Night
                        "midnight" -> Name.Midnight
                        "sunrise" -> Name.Sunrise
                        else -> null
                    }?.let { Named(it) }

                    private fun parseTime(time: String): Time? {
                        return timePattern.matchEntire(time)?.let {
                            Time(it.groups[1]!!.value.toInt(), it.groups[2]!!.value.toInt())
                        }
                    }

                    private fun parseTicks(ticks: String): Ticks? {
                        return ticks.toIntOrNull()?.let { Ticks(it) }
                    }
                }
            }

            companion object {
                @JvmStatic
                fun deserialize(config: Map<String, Any>): WarTime = WarTime(
                        Moment.deserialize(config["start"] as? String) ?: Moment.Named(Moment.Name.Sunset),
                        Moment.deserialize(config["start"] as? String) ?: Moment.Named(Moment.Name.Sunrise),
                        config["disable-leave-join-gang"] as? Boolean ?: true
                )
            }
        }

        companion object {
            @JvmStatic
            fun deserialize(config: Map<String, Any>): PeaceAndWar = PeaceAndWar(
                    WarTime.deserialize(config["war-time"] as Map<String, Any>)
            )
        }
    }

    data class Income(private val data: Map<String, Float>) : Map<String, Float> by data {
        companion object {
            @JvmStatic
            fun deserialize(config: Map<String, Any>): Income = Income(
                    config.mapValues { (it as? Double ?: 0.0).toFloat() }
            )
        }
    }

    companion object {
        @JvmStatic
        fun deserialize(config: Map<String, Any>): Config = Config(
                PowerLevels.deserialize(config["power-levels"] as Map<String, Any>),
                PeaceAndWar.deserialize(config["peace-and-war"] as Map<String, Any>),
                Income.deserialize(config["income"] as Map<String, Any>)
        )
    }
}

fun ConfigurationSection.toMap(): Map<String, Any> = this.getKeys(false).associateWith {
    when (val value = this[it]) {
        is ConfigurationSection -> {
            value.toMap()
        }
        else -> {
            value
        }
    }
}
