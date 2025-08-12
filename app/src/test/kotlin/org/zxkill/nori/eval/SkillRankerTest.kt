package org.zxkill.nori.eval

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.nori.skill.skill.Skill
import org.nori.skill.skill.Specificity
import org.zxkill.nori.MockSkill
import org.zxkill.nori.MockSkillContext


const val INPUT: String = "hi"

private fun getRanker(
    fallback: Skill<*>,
    vararg others: Skill<*>
): SkillRanker {
    return SkillRanker(listOf(*others), fallback)
}

private fun assertRanked(
    cr: SkillRanker,
    fallback: MockSkill,
    best: MockSkill
) {
    val result = cr.getBest(MockSkillContext, "")
    result shouldNotBe null

    withClue(if (result?.skill === fallback) {
        "Fallback skill returned by getBest"
    } else {
        "Skill with specificity ${result?.skill?.specificity} and score ${
            result?.score} returned by getBest"
    }) {
        result?.skill shouldBeSameInstanceAs best
    }
}

class SkillRankerTest : StringSpec({
    "all skills from a specificity level are evaluated even after a perfect score is found" {
        val ac1 = MockSkill(Specificity.HIGH, 1.00f)
        val ac2 = MockSkill(Specificity.HIGH, 0.93f)
        val ac3 = MockSkill(Specificity.HIGH, 1.00f)
        val acMed = MockSkill(Specificity.MEDIUM, 1.00f)
        val acLow = MockSkill(Specificity.LOW, 1.00f)

        val cr = getRanker(MockSkill(Specificity.LOW, 0.0f), ac1, acMed, ac2, acLow, ac3)
        cr.getBest(MockSkillContext, INPUT)

        ac1.scoreCalled.shouldBeTrue()
        ac2.scoreCalled.shouldBeTrue()
        ac3.scoreCalled.shouldBeTrue()
        acMed.scoreCalled.shouldBeFalse()
        acLow.scoreCalled.shouldBeFalse()
    }

    "if any of the high-specificity skills scores high enough, none of the less-specific ones are" +
            "evaluated, independent of the order they are passed to getRanker()" {
        val acHigh = MockSkill(Specificity.HIGH, 1.00f)
        val acMed = MockSkill(Specificity.MEDIUM, 1.00f)
        val acLow = MockSkill(Specificity.LOW, 1.00f)

        val cr = getRanker(MockSkill(Specificity.LOW, 0.0f), acMed, acHigh, acLow)
        cr.getBest(MockSkillContext, INPUT)

        acHigh.scoreCalled.shouldBeTrue()
        acMed.scoreCalled.shouldBeFalse()
        acLow.scoreCalled.shouldBeFalse()
    }

    "in a fixed specificity category, the best-score skill is chosen" {
        val fallback = MockSkill(Specificity.LOW, 0.0f)
        val best = MockSkill(Specificity.HIGH, 0.92f)
        val cr = getRanker(
            fallback,
            MockSkill(Specificity.MEDIUM, 0.95f),
            MockSkill(Specificity.HIGH, 0.71f),
            MockSkill(Specificity.HIGH, 0.91f),
            best,
            MockSkill(Specificity.LOW, 1.00f)
        )
        assertRanked(cr, fallback, best)
    }

    "chosen skill has high score but a low specificity, and other skills with lower score but " +
            "higher specificity are not chosen" {
        val fallback = MockSkill(Specificity.LOW, 0.0f)
        val best = MockSkill(Specificity.LOW, 1.0f)
        val cr = getRanker(
            fallback,
            MockSkill(Specificity.MEDIUM, 0.81f),
            MockSkill(Specificity.HIGH, 0.71f),
            MockSkill(Specificity.LOW, 0.85f),
            best,
            MockSkill(Specificity.HIGH, 0.32f)
        )
        assertRanked(cr, fallback, best)
    }

    "getBest should return null if there is no match even with the fallback" {
        val fallback = MockSkill(Specificity.LOW, 0.0f)
        val cr = getRanker(fallback, MockSkill(Specificity.LOW, 0.8f))
        val result = cr.getBest(MockSkillContext, INPUT)
        result shouldBe null // make sure the fallback is not returned (this was once the case)
    }

    "getFallbackSkill should return the fallback skill" {
        val fallback = MockSkill(Specificity.LOW, 0.0f)
        val cr = getRanker(fallback, MockSkill(Specificity.LOW, 0.8f))
        val gotFallback = cr.getFallbackSkill(MockSkillContext, INPUT)
        gotFallback.skill shouldBeSameInstanceAs fallback
        (gotFallback.skill as MockSkill).scoreCalled.shouldBeTrue()
    }
})
