package com.example.eden.character

/**
 * 角色注册表 —— 「换角色」的唯一入口。
 *
 * 目前只有一个角色（三玖）。以后要加角色 / 换角色时：
 * 把新角色对象加进 [all]，并让 [current] 指向它即可；
 * 界面统一读 [current]，就无需再改任何调用点。
 */
object Characters {

    /** 已注册的全部角色 */
    val all: List<CharacterPersona> = listOf(MikuPersona)

    /** 当前生效的角色 */
    val current: CharacterPersona = MikuPersona
}
