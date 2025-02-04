package dev.sora.relay.cheat.module.impl.combat

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.entity.EntityPlayer
import dev.sora.relay.game.event.EventTick
import dev.sora.relay.game.registry.itemDefinition
import dev.sora.relay.game.utils.AxisAlignedBB
import dev.sora.relay.game.utils.constants.EnumFacing
import dev.sora.relay.game.utils.toVector3i
import dev.sora.relay.utils.timing.TheTimer
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket

class ModuleSurround : CheatModule("Surround", CheatCategory.COMBAT) {

	private var fitInHoleValue by boolValue("FitInHole", true)
	private var onSneakValue by boolValue("OnSneak", true)
	private var placeDelayValue by intValue("PlaceDelay", 100, 100..1000)

	private val placeableDirections = arrayOf(EnumFacing.EAST, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.DOWN)
	private val delayTimer = TheTimer()

	private var holePosition: Vector3f? = null

	override fun onDisable() {
		holePosition = null
	}

	private val onTick = handle<EventTick> { event ->
		val session = event.session

		if (onSneakValue && !session.thePlayer.isSneaking) {
			holePosition = null
			return@handle
		}

		val basePosition = session.thePlayer.vec3PositionFeet.sub(0.5f, 0f, 0.5f)
		val roundPosition = basePosition.round()

		if (fitInHoleValue) {
			if (holePosition == null) {
				holePosition = roundPosition
			}
			if (basePosition.distance(holePosition!!) >= 0.1) {
				session.thePlayer.teleport(holePosition!!.add(0.5f, EntityPlayer.EYE_HEIGHT, 0.5f))
			}
		}

		if (!delayTimer.hasTimePassed(placeDelayValue))
			return@handle

		val blockToPlace = placeableDirections
			.map { roundPosition.toVector3i().add(it.unitVector.x, it.unitVector.y, it.unitVector.z) }
			.filter { session.theWorld.getBlockAt(it).identifier == "minecraft:air" }
		if (blockToPlace.isEmpty()) return@handle

		val slot = if (session.thePlayer.inventory.hand.itemDefinition.identifier == "minecraft:obsidian") -1
			else session.thePlayer.inventory.searchForItemInHotbar { it.itemDefinition.identifier == "minecraft:obsidian" }
		if (slot == null) {
			session.chat("Disabling due to no obsidian found in hotbar!")
			state = false
			return@handle
		}

		val bb = AxisAlignedBB(session.thePlayer.posX - .3f, session.thePlayer.posY - 1f, session.thePlayer.posZ - .3f,
			session.thePlayer.posX + .3f, session.thePlayer.posY + .8f, session.thePlayer.posZ + .3f)

		// TODO: better block searching
		blockToPlace.forEach { block ->
			// check collide
			if (bb.intersects(block.x + 0f, block.y + 0f, block.z + 0f, block.x + 1f, block.y + 1f, block.z + 1f))
				return@forEach

			val (facing, _) = EnumFacing.values().map { it to block.sub(it.unitVector) }
				.filter { session.theWorld.getBlockAt(it.second).identifier != "minecraft:air" }
				.minByOrNull { it.second.distance(roundPosition.toVector3i()) } ?: return@forEach

			val originalSlot = if (slot != -1) {
				session.sendPacket(PlayerHotbarPacket().apply {
					selectedHotbarSlot = slot
					isSelectHotbarSlot = true
					containerId = ContainerId.INVENTORY
				})
				session.thePlayer.inventory.heldItemSlot
			} else -1

			session.thePlayer.placeBlock(block, facing)
			delayTimer.reset()

			if (slot != -1) {
				session.sendPacket(PlayerHotbarPacket().apply {
					selectedHotbarSlot = originalSlot
					isSelectHotbarSlot = true
					containerId = ContainerId.INVENTORY
				})
			}

			return@handle
		}
	}
}
