package org.zxkill.nori.skills.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.nori.skill.context.SkillContext
import org.nori.skill.skill.InteractionPlan
import org.nori.skill.skill.SkillOutput
import org.zxkill.nori.R
import org.zxkill.nori.io.graphical.Headline
import org.zxkill.nori.util.RecognizeEverythingSkill
import org.zxkill.nori.util.ShareUtils
import org.zxkill.nori.util.getString

class SearchOutput(
    private val results: List<Data>?,
    private val askAgain: Boolean,
) : SkillOutput {
    class Data (
        val title: String,
        val thumbnailUrl: String,
        val url: String,
        val description: String,
    )

    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
        if (results == null)
            R.string.skill_search_what_question
        else if (results.isNotEmpty())
            R.string.skill_search_here_is_what_i_found
        else if (askAgain)
            R.string.skill_search_no_results
        else
            // if the search continues to return 0 results, don't keep asking
            R.string.skill_search_no_results_stop
    )

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan {
        if (!results.isNullOrEmpty() || !askAgain) {
            return InteractionPlan.FinishInteraction
        }

        val searchAnythingSkill = object : RecognizeEverythingSkill(SearchInfo) {
            override suspend fun generateOutput(
                ctx: SkillContext,
                inputData: String
            ): SkillOutput {
                // ask again only if this is the first time we ask the user to provide what
                // to search for, otherwise we could continue asking indefinitely
                return SearchOutput(searchOnDuckDuckGo(ctx, inputData), results == null)
            }
        }

        return InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(
                SearchSkill(SearchInfo),
                searchAnythingSkill,
            ),
        )
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        if (results.isNullOrEmpty()) {
            Headline(text = getSpeechOutput(ctx))
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (result in results) {
                    SearchResult(result)
                }
            }
        }
    }
}

@Composable
private fun SearchResult(data: SearchOutput.Data) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.clickable {
            ShareUtils.openUrlInBrowser(context, data.url)
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = data.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .width(16.dp)
                    .aspectRatio(1.0f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = data.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = data.description,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
