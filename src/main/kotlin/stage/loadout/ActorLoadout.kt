package xyz.qwewqa.relive.simulator.core.stage.loadout

import xyz.qwewqa.relive.simulator.core.stage.actor.Actor
import xyz.qwewqa.relive.simulator.core.stage.memoir.Memoir

data class ActorLoadout(
    val name: String,
    val dress: Dress,
    val memoir: Memoir? = null,
) {
    fun create() = Actor(name, dress, memoir)
}