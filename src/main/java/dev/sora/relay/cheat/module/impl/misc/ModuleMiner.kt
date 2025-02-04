package dev.sora.relay.cheat.module.impl.misc

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.cheat.value.NamedChoice
import dev.sora.relay.game.GameSession
import dev.sora.relay.game.entity.EntityPlayerSP
import dev.sora.relay.game.event.EventTick
import dev.sora.relay.game.utils.MineUtils
import dev.sora.relay.game.utils.distance
import dev.sora.relay.game.utils.removeNetInfo
import dev.sora.relay.game.utils.toVector3iFloor
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket

class ModuleMiner : CheatModule("Miner", CheatCategory.MISC) {

	private var blockValue by stringValue("Block", "minecraft:bed")
	private var rangeValue by intValue("Range", 5, 2..7)
	private var swingValue by listValue("Swing", EntityPlayerSP.SwingMode.values(), EntityPlayerSP.SwingMode.BOTH)
	private var actionValue by listValue("Action", Action.values(), Action.BREAK)

	private var pos: Vector3i? = null
	private var lastingBreakTime = 0
	private var freshBreak = false

	override fun onDisable() {
		pos = null
		lastingBreakTime = 0
		freshBreak = false
	}

	private val onTick = handle<EventTick> { event ->
		val session = event.session

		if (pos == null || session.thePlayer.vec3PositionFeet.distance(pos!!) > rangeValue) {
			pos = find(session)?.also {
				lastingBreakTime = MineUtils.calculateBreakTime(session, session.theWorld.getBlockAt(it))
				freshBreak = true
			}
		} else if (session.theWorld.getBlockAt(pos!!).identifier != blockValue) {
			pos = null
		}

		val pos = pos ?: return@handle

		when(actionValue) {
			Action.USE -> {
				session.thePlayer.useItem(ItemUseTransaction().apply {
					actionType = 0
					blockPosition = pos
					blockFace = 0
					hotbarSlot = session.thePlayer.inventory.heldItemSlot
					itemInHand = session.thePlayer.inventory.hand.removeNetInfo()
					playerPosition = session.thePlayer.vec3Position
					clickPosition = Vector3f.from(Math.random(), Math.random(), Math.random())
					blockDefinition = session.theWorld.getBlockAt(pos)
				})
			}
			Action.BREAK -> {
				if (lastingBreakTime == 0) {
					if (session.thePlayer.blockBreakServerAuthoritative) {
						// vanilla sends packet like this, even though there's no block to destroy
						session.thePlayer.blockAction(PlayerBlockActionData().apply {
							action = PlayerActionType.BLOCK_CONTINUE_DESTROY
							blockPosition = pos
							face = 1
						})
						session.thePlayer.blockAction(PlayerBlockActionData().apply {
							action = PlayerActionType.BLOCK_PREDICT_DESTROY
							blockPosition = pos
							face = 1
						})
					} else {
						session.thePlayer.blockAction(PlayerBlockActionData().apply {
							action = PlayerActionType.STOP_BREAK
							blockPosition = Vector3i.ZERO
							face = 1
						})
						session.thePlayer.blockAction(PlayerBlockActionData().apply {
							action = PlayerActionType.CONTINUE_BREAK
							blockPosition = pos
							face = 1
						})
						session.sendPacket(InventoryTransactionPacket().apply {
							transactionType = InventoryTransactionType.ITEM_USE
							actionType = 2
							blockPosition = pos
							blockFace = 1
							itemInHand = ItemData.AIR
							playerPosition = session.thePlayer.vec3Position
							clickPosition = Vector3f.ZERO
							blockDefinition = session.blockMapping.getDefinition(0)
						})
					}
				} else if (lastingBreakTime < 0) {
					session.thePlayer.blockAction(PlayerBlockActionData().apply {
						action = PlayerActionType.ABORT_BREAK
						blockPosition = pos
						face = 0
					})
//					session.sendPacketToClient(UpdateBlockPacket().apply {
//						blockPosition = pos
//						this.definition = session.blockMapping.let { it.getDefinition(it.getRuntimeByIdentifier("minecraft:air")) }
//					})
					this.pos = null
				}
				if (freshBreak) {
					session.thePlayer.blockAction(PlayerBlockActionData().apply {
						action = PlayerActionType.START_BREAK
						blockPosition = pos
						face = 1
					})
					freshBreak = false
				} else if (!session.thePlayer.blockBreakServerAuthoritative) {
					session.thePlayer.blockAction(PlayerBlockActionData().apply {
						action = PlayerActionType.CONTINUE_BREAK
						blockPosition = pos
						face = 1
					})
				}
				lastingBreakTime--
			}
		}
		session.thePlayer.swing(swingValue)
	}

//	private val onPacketOutbound = handle<EventPacketOutbound> { event ->
//		val packet = event.packet
//
//		if (packet is PlayerAuthInputPacket && packet.playerActions.isNotEmpty()) {
//			println(packet.playerActions.joinToString())
//		}
//	}

	private fun find(session: GameSession): Vector3i? {
		val pos = session.thePlayer.vec3Position
		val floorPos = pos.toVector3iFloor()
		val radius = rangeValue + 1

		var nearestBlockDistance = Float.MAX_VALUE
		var nearestBlock: Vector3i? = null

		for (x in radius downTo -radius + 1) {
			for (y in radius downTo -radius + 1) {
				for (z in radius downTo -radius + 1) {
					val blockPos = Vector3i.from(floorPos.x + x, floorPos.y + y, floorPos.z + z)
					val block = session.theWorld.getBlockAt(blockPos)

					if (block.identifier != blockValue) continue

					val distance = pos.distance(blockPos)
					if (distance > rangeValue) continue
					if (nearestBlockDistance < distance) continue

					nearestBlockDistance = distance
					nearestBlock = blockPos
				}
			}
		}

		return nearestBlock
	}

	private enum class Action(override val choiceName: String) : NamedChoice {
		BREAK("Break"),
		USE("Use")
	}
}
