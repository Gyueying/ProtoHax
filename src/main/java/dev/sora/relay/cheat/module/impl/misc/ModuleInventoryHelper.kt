package dev.sora.relay.cheat.module.impl.misc

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.cheat.value.NamedChoice
import dev.sora.relay.game.GameSession
import dev.sora.relay.game.entity.EntityPlayerSP
import dev.sora.relay.game.event.EventPacketInbound
import dev.sora.relay.game.event.EventPacketOutbound
import dev.sora.relay.game.event.EventTick
import dev.sora.relay.game.inventory.AbstractInventory
import dev.sora.relay.game.inventory.PlayerInventory
import dev.sora.relay.game.registry.isBlock
import dev.sora.relay.game.registry.itemDefinition
import dev.sora.relay.game.utils.constants.ItemTags
import dev.sora.relay.game.utils.toVector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket

class ModuleInventoryHelper : CheatModule("InventoryHelper", CheatCategory.MISC) {

    private var stealChestValue by boolValue("StealChest", true)
    private var guiOpenValue by boolValue("GuiOpen", false)

	/**
	 * FIXME
	 */
    private var simulateInventoryValue by boolValue("SimulateInventory", false)
    private var autoCloseValue by boolValue("AutoClose", false)
    private var throwUnnecessaryValue by boolValue("ThrowUnnecessary", true)
    private var swingValue by listValue("Swing", EntityPlayerSP.SwingMode.values(), EntityPlayerSP.SwingMode.BOTH)
    private val cpsValue = clickValue(value = 2..4)
    private var sortArmorValue by boolValue("Armor", true)
    private var sortOffhandValue by listValue("Offhand", SortOffhandMode.values(), SortOffhandMode.TOTEM)
    private var sortSwordValue by intValue("SortSword", 0, -1..8)
    private var sortPickaxeValue by intValue("SortPickaxe", 5, -1..8)
    private var sortAxeValue by intValue("SortAxe", 6, -1..8)
    private var sortBlockValue by intValue("SortBlock", 7, -1..8)
    private var sortGAppleValue by intValue("SortGApple", 2, -1..8)
    private var noSortNoCloseValue by boolValue("NoCloseIfNoSort", true)

    private val sortArmor = arrayOf(
        Sort(PlayerInventory.SLOT_HELMET, ItemTags.TAG_IS_HELMET),
        Sort(PlayerInventory.SLOT_CHESTPLATE, ItemTags.TAG_IS_CHESTPLATE),
        Sort(PlayerInventory.SLOT_LEGGINGS, ItemTags.TAG_IS_LEGGINGS),
        Sort(PlayerInventory.SLOT_BOOTS, ItemTags.TAG_IS_BOOTS)
    )

    private var sorted = true
    private var hasSimulated = false
    private var hasSimulatedWaitForClose = false

    override fun onDisable() {
        sorted = false
        hasSimulated = false
        hasSimulatedWaitForClose = false
    }

	private val handleTick = handle<EventTick> { event ->
		if (!cpsValue.canClick) {
			return@handle
		}

		val player = event.session.thePlayer
		val openContainer = player.openContainer

		if (stealChestValue && openContainer != null && openContainer !is PlayerInventory) {
			// steal items
			openContainer.content.forEachIndexed { index, item ->
				if (item.itemDefinition.isNecessaryItem(item) && !item.itemDefinition.hasBetterItem(openContainer, index, strictMode = false) && !item.itemDefinition.hasBetterItem(player.inventory)) {
					val slot = player.inventory.findEmptySlot() ?: return@handle
					openContainer.moveItem(index, slot, player.inventory, event.session)

					cpsValue.click()
					sorted = true
					return@handle
				}
			}
		} else if (!guiOpenValue || openContainer != null) {
			// drop garbage
			player.inventory.content.forEachIndexed { index, item ->
				if (item == ItemData.AIR) return@forEachIndexed
				if (item.itemDefinition.hasBetterItem(player.inventory, index) || (throwUnnecessaryValue && !item.itemDefinition.isNecessaryItem(item))) {
					if (checkFakeOpen(event.session)) return@handle
					player.inventory.dropItem(index, event.session)
					// player will swing if they drop an item
					player.swing(swingValue)

					cpsValue.click()
					sorted = true
					return@handle
				}
			}

			// sort items and wear armor
			val sorts = getSorts(player.inventory)
			sorts.forEach {
				if (it.sort(player.inventory, event.session)) {
					cpsValue.click()
					sorted = true
					return@handle
				}
			}

			if (openContainer == null) {
				if (hasSimulated) {
					session.netSession.outboundPacket(ContainerClosePacket().apply {
						id = 0
						isServerInitiated = false
					})
					hasSimulated = false
					hasSimulatedWaitForClose = true
				}
				cpsValue.click()
				return@handle
			}
		} else {
			cpsValue.click()
			return@handle
		}

		if (autoCloseValue && (!noSortNoCloseValue || sorted)) {
			session.sendPacketToClient(ContainerClosePacket().apply {
				id = openContainer.containerId.toByte()
				isServerInitiated = true // maybe? this field is true in nukkit
			})
		}
	}

    private fun getSorts(inventory: AbstractInventory): List<Sort> {
        val sorts = mutableListOf<Sort>()
        if (sortArmorValue) {
            sorts.addAll(sortArmor)
        }
        if (sortOffhandValue != SortOffhandMode.NONE) {
			val (totemPriority, shieldPriority) = if (sortOffhandValue == SortOffhandMode.SHIELD) 1f to 2f
				else 2f to 1f

			sorts.add(Sort(PlayerInventory.SLOT_OFFHAND) {
				val identifier = it.itemDefinition.identifier
				if (identifier == "minecraft:totem_of_undying") totemPriority
				else if (identifier == "minecraft:shield") shieldPriority
				else 0f
			})
        }
        if (sortSwordValue != -1) {
            sorts.add(Sort(sortSwordValue, ItemTags.TAG_IS_SWORD))
        }
        if (sortPickaxeValue != -1) {
            sorts.add(Sort(sortPickaxeValue, ItemTags.TAG_IS_PICKAXE))
        }
        if (sortAxeValue != -1) {
            sorts.add(Sort(sortAxeValue, ItemTags.TAG_IS_AXE))
        }
        if (sortBlockValue != -1 && !inventory.content[sortBlockValue].isBlock()) {
            sorts.add(Sort(sortBlockValue) { item ->
                if (item.isBlock()) item.count.toFloat() else 0f
            })
        }
        if (sortGAppleValue != -1) {
            sorts.add(Sort(sortGAppleValue) { item ->
                if (item.itemDefinition.identifier == "minecraft:golden_apple") 1f else 0f
            })
        }
        return sorts
    }

	private val handlePacketInbound = handle<EventPacketInbound> { event ->
		val packet = event.packet

		if (hasSimulated && packet is ContainerOpenPacket && packet.id == 0.toByte()) {
			event.cancel()
		} else if (hasSimulatedWaitForClose && packet is ContainerClosePacket && packet.id == 0.toByte()) {
			// inventories gui will no longer display if this packet is received or unable to receive the one that required by the client
			event.cancel()
			hasSimulatedWaitForClose = false
		}
	}

	private val handlePacketOutbound = handle<EventPacketOutbound> { event ->
		val packet = event.packet

		if (hasSimulated && packet is InteractPacket && packet.action == InteractPacket.Action.OPEN_INVENTORY) {
			hasSimulated = false
			event.cancel()
			// client only display inventory gui if server accepts it
			event.session.sendPacketToClient(ContainerOpenPacket().apply {
				id = 0.toByte()
				type = ContainerType.INVENTORY
				blockPosition = event.session.thePlayer.vec3Position.toVector3i()
				uniqueEntityId = event.session.thePlayer.uniqueEntityId
			})
		}
	}

    private fun checkFakeOpen(session: GameSession): Boolean {
        if (!hasSimulated && session.thePlayer.openContainer == null && simulateInventoryValue) {
            session.netSession.outboundPacket(InteractPacket().apply {
                runtimeEntityId = session.thePlayer.runtimeEntityId
                action = InteractPacket.Action.OPEN_INVENTORY
            })
            hasSimulated = true
			cpsValue.click()
			sorted = true
            return true
        }
        return false
    }

	private inner class Sort(val slot: Int, val requiresBestItem: Boolean = false, val judge: (ItemData) -> Float) {

        constructor(slot: Int, judgeTag: String, requiresBestItem: Boolean = true) : this(slot, requiresBestItem, { item ->
			val def = item.itemDefinition
            if (def.tags.contains(judgeTag)) {
                def.getTier().toFloat()
            } else {
                0f
            }
        })

        fun sort(inventory: AbstractInventory, session: GameSession): Boolean {
			if (!requiresBestItem && judge(inventory.content[slot]) > 0) {
				return false
			}
            val bestSlot = inventory.findBestItem(slot) { item ->
                judge(item)
            } ?: return false
            if (bestSlot == slot) return false
            if (checkFakeOpen(session)) return true

            inventory.moveItem(bestSlot, slot, inventory, session)
            return true
        }
    }

	private enum class SortOffhandMode(override val choiceName: String) : NamedChoice {
		SHIELD("Shield"),
		TOTEM("Totem"),
		NONE("None")
	}
}
