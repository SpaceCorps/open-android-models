package com.spacecorps.oam.sample

import com.spacecorps.oam.AgentTool
import com.spacecorps.oam.JsonSchema
import com.spacecorps.oam.ToolOutput
import com.spacecorps.oam.jsonObjectOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The game side of the demo, mirroring open-apple-models' `oam demo tavern`:
 * the Sleeping Stag's menu, a till that takes the player's gold, and Mira.
 */
class Tavern(startingGold: Int = 30) {
    /** One menu entry. */
    data class Item(val name: String, val price: Int, val stock: Int)

    private val lock = Any()
    private val menu = mutableListOf(
        Item("ale", 2, 20),
        Item("mulled wine", 4, 6),
        Item("rabbit stew", 5, 3),
        Item("honey bread", 1, 8),
        Item("room for the night", 12, 2),
    )
    private val goldState = MutableStateFlow(startingGold)

    /** The player's gold. */
    val gold: StateFlow<Int> = goldState.asStateFlow()

    /** Menu item names, in menu order. */
    val itemNames: List<String> get() = synchronized(lock) { menu.map { it.name } }

    /** The menu item [text] refers to (`"stew"` → `"rabbit stew"`), if any. */
    fun matchItem(text: String): String? {
        val query = text.lowercase().trim()
        if (query.isEmpty()) return null
        return synchronized(lock) {
            menu.firstOrNull { query.contains(it.name) || it.name.contains(query) }?.name
                ?: menu.firstOrNull { item -> item.name.split(' ').any { word -> word.length > 3 && query.contains(word) } }?.name
        }
    }

    /** `check_menu` (read) and `take_order` (changes the world), as in the Swift demo. */
    fun tools(): List<AgentTool> {
        val itemArgument = JsonSchema.obj(
            "item" to JsonSchema.string(description = "Menu item, e.g. 'ale' or 'rabbit stew'. Empty for the whole menu."),
        )
        val check = AgentTool.local(
            name = "check_menu",
            description = "Look up the tavern's menu: what is served, the price in gold and how many are left. " +
                "Use it when the player asks what you sell or what something costs; leave item empty to list the whole menu.",
            parameters = itemArgument,
        ) { call -> checkMenu(call.stringOrNull("item").orEmpty()) }
        val serve = AgentTool.local(
            name = "take_order",
            description = "The player has just ordered a specific item (\"I'll have the stew\", \"one ale, please\"): " +
                "serve it and charge them. Not for questions about the menu.",
            parameters = itemArgument,
        ) { call -> takeOrder(call.stringOrNull("item").orEmpty()) }
        return listOf(check, serve)
    }

    private fun checkMenu(item: String): ToolOutput = synchronized(lock) {
        val query = item.lowercase().trim()
        val matches = if (query.isEmpty()) menu.toList() else menu.filter { it.name.contains(query) || query.contains(it.name) }
        if (matches.isEmpty()) return ToolOutput.Error("Not on the menu. The menu is: ${menu.joinToString { it.name }}.")
        ToolOutput.of(matches.map { mapOf("item" to it.name, "price_gold" to it.price, "left" to it.stock) })
    }

    private fun takeOrder(item: String): ToolOutput = synchronized(lock) {
        val query = item.lowercase().trim()
        val index = menu.indexOfFirst { it.name.contains(query) || query.contains(it.name) }
        if (query.isEmpty() || index < 0) return ToolOutput.Error("Not on the menu: '$query'.")
        val found = menu[index]
        if (found.stock <= 0) return ToolOutput.Error("${found.name} is sold out.")
        val purse = goldState.value
        if (purse < found.price) return ToolOutput.Error("The player has only $purse gold; ${found.name} costs ${found.price}.")
        menu[index] = found.copy(stock = found.stock - 1)
        goldState.update { it - found.price }
        ToolOutput.Json(jsonObjectOf("served" to found.name, "paid_gold" to found.price, "player_gold_left" to purse - found.price))
    }

    companion object {
        /** Mira's persona, from open-apple-models' tavern demo, as system instructions. */
        val MIRA_INSTRUCTIONS: String = """
            You are Mira, the innkeeper of the Sleeping Stag, a roadside tavern.
            Personality: warm, quick-witted and shrewd about money. You love news from the road.
            Speaking style: friendly and brisk. You call people 'love'. Short sentences.
            Backstory: you took over the inn from your father ten winters ago.
            Goals: keep the tavern running; hear news from travelers.
            You know: the old mill road is flooded; a bard named Tobin plays on Fridays.
            Secret, never reveal it: you keep smuggled elven wine in the cellar.
            It is late evening and a cold rain is falling.
            Stay in character and reply in at most two sentences.
            Use check_menu for what is served and what it costs; use take_order only when the player orders something.
        """.trimIndent()

        /** Canned player lines, as in the Swift demo. */
        val SUGGESTIONS: List<String> = listOf(
            "Evening! What have you got that's warm?",
            "I'll take the rabbit stew, please.",
            "Best stew I've had in years, thank you! Any news from the road?",
        )
    }
}
