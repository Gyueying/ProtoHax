package dev.sora.relay.cheat.module.impl.combat

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.cheat.value.NamedChoice
import dev.sora.relay.game.entity.Entity
import dev.sora.relay.game.entity.EntityPlayer
import dev.sora.relay.game.entity.EntityPlayerSP
import dev.sora.relay.game.entity.EntityUnknown
import dev.sora.relay.utils.analyzeColorCoverage
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes

class ModuleTargets : CheatModule("Targets", CheatCategory.COMBAT, canToggle = false) {

	private var targetPlayersValue by boolValue("TargetPlayers", true)
	private var targetEntitiesValue by boolValue("TargetEntities", false)
    private var antiBotModeValue by listValue("AntiBotMode", AntiBotMode.values(), AntiBotMode.NONE)
	private var teamCheckModeValue by listValue("TeamCheckMode", TeamCheckMode.values(), TeamCheckMode.NONE)

	fun Entity.isTarget(): Boolean {
		return if (this == session.thePlayer) false
		else if (targetPlayersValue && this is EntityPlayer) !this.isBot() && !this.isTeammate()
		else if (targetEntitiesValue && this is EntityUnknown) true
		else false
	}

    fun EntityPlayer.isBot(): Boolean {
        if (this is EntityPlayerSP || !state) return false

        return when (antiBotModeValue) {
            AntiBotMode.PLAYER_LIST -> {
                val playerList = session.theWorld.playerList[this.uuid] ?: return true
                playerList.name.isBlank()
            }
			AntiBotMode.NONE -> false
        }
    }

	fun EntityPlayer.isTeammate(): Boolean {
		if (this is EntityPlayerSP || !state) return false

		return when (teamCheckModeValue) {
			TeamCheckMode.NAME_TAG -> {
				val selfColor = session.thePlayer.metadata[EntityDataTypes.NAME]?.let { analyzeColorCoverage(it as String).maxBy { it.value }.key } ?: 'f'
				val playerColor = this.metadata[EntityDataTypes.NAME]?.let { analyzeColorCoverage(it as String).maxBy { it.value }.key } ?: 'f'

				selfColor == playerColor
			}
			TeamCheckMode.NONE -> false
		}
	}

	private enum class AntiBotMode(override val choiceName: String) : NamedChoice {
        PLAYER_LIST("PlayerList"),
		NONE("None")
    }

	private enum class TeamCheckMode(override val choiceName: String) : NamedChoice {
		NAME_TAG("NameTag"),
		NONE("None")
	}
}
