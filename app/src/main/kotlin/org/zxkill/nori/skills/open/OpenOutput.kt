package org.zxkill.nori.skills.open

import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import org.nori.skill.context.SkillContext
import org.nori.skill.skill.SkillOutput
import org.zxkill.nori.R
import org.zxkill.nori.io.graphical.Headline
import org.zxkill.nori.util.getString

private val TAG = OpenOutput::class.simpleName

class OpenOutput(
    private val appName: String?,
    private val packageName: String?,
) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = if (appName == null) {
        ctx.getString(R.string.skill_open_could_not_understand)
    } else if (packageName == null) {
        ctx.getString(R.string.skill_open_unknown_app, appName)
    } else {
        ctx.getString(R.string.skill_open_opening, appName)
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        if (appName == null || packageName == null) {
            Headline(text = getSpeechOutput(ctx))

        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val context = LocalContext.current
                val icon = remember {
                    try {
                        context.packageManager.getApplicationIcon(packageName)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.e(TAG, "Could not load icon for $packageName", e)
                        null
                    }
                }

                if (icon != null) {
                    Image(
                        painter = rememberDrawablePainter(icon),
                        contentDescription = appName,
                        modifier = Modifier
                            .fillMaxWidth(0.2f)
                            .aspectRatio(1.0f),
                    )

                    Spacer(modifier = Modifier.width(8.dp))
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = getSpeechOutput(ctx),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = packageName,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
