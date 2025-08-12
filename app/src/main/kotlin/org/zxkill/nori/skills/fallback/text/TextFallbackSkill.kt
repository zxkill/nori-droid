package org.zxkill.nori.skills.fallback.text

import org.nori.skill.context.SkillContext
import org.nori.skill.skill.SkillInfo
import org.nori.skill.skill.SkillOutput
import org.zxkill.nori.util.RecognizeEverythingSkill

class TextFallbackSkill(correspondingSkillInfo: SkillInfo) :
    RecognizeEverythingSkill(correspondingSkillInfo) {
    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput {
        return TextFallbackOutput(
            // we ask to repeat only if we have not asked already just in the previous interaction
            askToRepeat = (ctx.previousOutput as? TextFallbackOutput)?.let { !it.askToRepeat }
                ?: true
        )
    }
}
