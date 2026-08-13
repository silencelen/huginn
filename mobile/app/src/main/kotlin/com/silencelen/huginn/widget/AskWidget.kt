package com.silencelen.huginn.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.silencelen.huginn.R

/**
 * The ask bar: a search-bar-shaped widget whose only job is to get a question
 * into huginn in the fewest possible motions — the same shape as a launcher's
 * quick-search widget, because that is the exact interaction being borrowed.
 *
 * A home-screen widget cannot host a real text field (RemoteViews has no
 * editable input; the search widgets it resembles are tappable drawings too),
 * so the bar opens [AskActivity]: a thin overlay with the keyboard already up.
 * Static on purpose — no state, no updates, nothing to refresh.
 */
class AskWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                AskBar()
            }
        }
    }
}

class AskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AskWidget()
}

@Composable
private fun AskBar() {
    val context = LocalContext.current
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(52.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(26.dp)
                .clickable(actionStartActivity(Intent(context, AskActivity::class.java)))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_stat_huginn),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.size(20.dp),
            )
            Spacer(GlanceModifier.width(12.dp))
            Text(
                "Ask huginn",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
            )
        }
    }
}
