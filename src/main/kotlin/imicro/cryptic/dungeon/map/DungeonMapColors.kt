package imicro.cryptic.dungeon.map

import imicro.cryptic.feature.DungeonMap

/**
 * The palette a dungeon map is drawn in.
 *
 * The defaults are dtMap's (BSD 3-Clause, Copyright (c) 2026 rice.who), which
 * are in turn the colours Hypixel paints the map item with, so an untouched map
 * reads the same as the one in your hand. Each is a setting, because which room
 * type should shout at you is a matter of taste and of what you are running.
 */
object DungeonMapColors {
	const val BLOOD = 0xFF0000
	const val NORMAL = 0x6B3A11
	const val PUZZLE = 0x750085
	const val CHAMPION = 0xFEDF00
	const val TRAP = 0xD87F33
	const val ENTRANCE = 0x148500
	const val FAIRY = 0xF4138B
	const val RARE = 0xFFCB59
	const val UNOPENED = 0x1E1E1E
	const val WITHER_DOOR = 0x000000

	fun room(type: DungeonRoom.Type): Int = opaque(
		when (type) {
			DungeonRoom.Type.BLOOD -> DungeonMap.bloodColor.rgb
			DungeonRoom.Type.NORMAL -> DungeonMap.normalColor.rgb
			DungeonRoom.Type.PUZZLE -> DungeonMap.puzzleColor.rgb
			DungeonRoom.Type.CHAMPION -> DungeonMap.championColor.rgb
			DungeonRoom.Type.TRAP -> DungeonMap.trapColor.rgb
			DungeonRoom.Type.ENTRANCE -> DungeonMap.entranceColor.rgb
			DungeonRoom.Type.FAIRY -> DungeonMap.fairyColor.rgb
			DungeonRoom.Type.RARE -> DungeonMap.rareColor.rgb
			DungeonRoom.Type.UNKNOWN -> DungeonMap.unexploredColor.rgb
		},
	)

	fun unexplored(): Int = opaque(DungeonMap.unexploredColor.rgb)

	fun door(type: DungeonDoor.Type): Int = opaque(
		when (type) {
			DungeonDoor.Type.NORMAL -> DungeonMap.normalDoorColor.rgb
			DungeonDoor.Type.WITHER -> DungeonMap.witherDoorColor.rgb
			DungeonDoor.Type.BLOOD -> DungeonMap.bloodDoorColor.rgb
			DungeonDoor.Type.ENTRANCE -> DungeonMap.entranceDoorColor.rgb
		},
	)

	/** How much of its colour a room keeps before anyone has opened it. */
	fun darken(argb: Int): Int {
		val amount = (DungeonMap.darkenUnexplored.value / 100.0).toFloat()
		val alpha = argb and 0xFF000000.toInt()
		val red = (((argb ushr 16) and 0xFF) * amount).toInt()
		val green = (((argb ushr 8) and 0xFF) * amount).toInt()
		val blue = ((argb and 0xFF) * amount).toInt()
		return alpha or (red shl 16) or (green shl 8) or blue
	}

	private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)
}
