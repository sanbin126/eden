package com.example.eden.domain.character

/**
 * 角色配置契约 —— 「换角色」总开关的接口。
 *
 * 想让 App 换成别的角色，只需：
 *  1. 新建一个实现本接口的对象（id / 显示名 / 立绘 / Q 版图）；
 *  2. 在 [Characters] 注册表里把 [Characters.current] 指向它；
 *  3. 把 themes.xml 里 edenCharPortrait / edenCharChibi 指到新角色的图
 *     （或让界面统一走 [Characters.current]，二者任选，推荐后者）。
 */
interface CharacterPersona {
    /** 角色唯一标识，用于聊天记录分表 / 存储 */
    val id: String

    /** 角色显示名（界面标题、头像旁的称呼等） */
    val name: String

    /** 主界面立绘 */
    val portraitRes: Int

    /** 对话/小游戏里的 Q 版小人图 */
    val chibiRes: Int
}
