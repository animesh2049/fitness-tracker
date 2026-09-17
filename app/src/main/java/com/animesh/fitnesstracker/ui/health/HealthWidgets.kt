package com.animesh.fitnesstracker.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Pool
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animesh.fitnesstracker.data.model.ActivityKind
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.MonoNumberLarge
import com.animesh.fitnesstracker.ui.theme.PlexMono
import com.animesh.fitnesstracker.ui.theme.Tokens

/** Card title style (13 sp semibold) used at the top of every health card. */
val CardTitleStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)

/** 12 sp mono for the right side of card headers ("47 now", "50 to 158 bpm"). */
val MonoCaption = MonoNumber.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** 20 sp mono stat for the three-up and two-up tiles of the health screens. */
val MonoTile = MonoNumber.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)

/** Back arrow plus eyebrow, title and subtitle, the header of every health sub-screen. */
@Composable
fun HealthBackHeader(eyebrow: String, title: String, subtitle: String?, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.iconButtonColors(contentColor = Tokens.Text)
        ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        Column(Modifier.weight(1f).padding(start = 4.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        }
        if (trailing != null) trailing()
    }
}

/** 44 dp square bordered icon button on the surface colour (header watch button, switcher arrows). */
@Composable
fun SquareIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(44.dp).clip(shape).background(Tokens.Surface).border(1.dp, Tokens.Border, shape),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Tokens.Text, disabledContentColor = Tokens.Faint)
    ) { Icon(icon, contentDescription = contentDescription) }
}

/** Previous and next buttons around a centre pill with the current day or night. */
@Composable
fun DaySwitcher(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    previousDescription: String = "Previous day",
    nextDescription: String = "Next day",
    showCalendar: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SquareIconButton(Icons.Outlined.ChevronLeft, previousDescription, onPrevious, enabled = previousEnabled)
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.weight(1f).height(44.dp).clip(shape).background(Tokens.Surface).border(1.dp, Tokens.Border, shape),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showCalendar) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = Tokens.Muted, modifier = Modifier.size(16.dp))
                Box(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.titleSmall, color = Tokens.Text, maxLines = 1)
        }
        SquareIconButton(Icons.Outlined.ChevronRight, nextDescription, onNext, enabled = nextEnabled)
    }
}

/** The outlined Material icon that stands in for the design's stroke icon of a sport. */
fun activityIcon(kind: ActivityKind): ImageVector = when (kind) {
    ActivityKind.STRENGTH -> Icons.Outlined.FitnessCenter
    ActivityKind.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
    ActivityKind.RUN -> Icons.AutoMirrored.Outlined.DirectionsRun
    ActivityKind.CYCLE -> Icons.AutoMirrored.Outlined.DirectionsBike
    ActivityKind.HIKE -> Icons.Outlined.Hiking
    ActivityKind.YOGA -> Icons.Outlined.SelfImprovement
    ActivityKind.CARDIO -> Icons.Outlined.MonitorHeart
    ActivityKind.SWIM -> Icons.Outlined.Pool
    ActivityKind.OTHER -> Icons.Outlined.Timeline
}

/** One activity in a list: 40 dp icon box, name (plus an optional Linked pill), meta line, duration and kcal. */
@Composable
fun ActivityRow(kind: ActivityKind, name: String, meta: String, duration: String, sub: String?, linked: Boolean, onClick: () -> Unit) {
    AppCard(padding = PaddingValues(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Tokens.Surface2), contentAlignment = Alignment.Center) {
                Icon(activityIcon(kind), contentDescription = kind.label, tint = Tokens.Accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name, style = MaterialTheme.typography.titleSmall, color = Tokens.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (linked) LinkedPill()
                }
                Text(meta, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(duration, style = MonoNumberLarge.copy(fontWeight = FontWeight.SemiBold), color = Tokens.Text)
                if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Normal), color = Tokens.Dim)
            }
        }
    }
}

/** 18 dp accent-outlined "Linked" pill. */
@Composable
fun LinkedPill() {
    Box(
        Modifier.height(18.dp).border(1.dp, Tokens.AccentBorder, RoundedCornerShape(9.dp)).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Linked", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp), color = Tokens.Accent)
    }
}

/** Accent filled pill such as "Score 81". */
@Composable
fun AccentPill(text: String, height: Int = 24) {
    Box(
        Modifier.height(height.dp).clip(RoundedCornerShape((height / 2).dp)).background(Tokens.Accent).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp), color = Tokens.AccentInk, maxLines = 1)
    }
}

/** Horizontal bar split into coloured segments proportional to [weights]; a muted track when everything is zero. */
@Composable
fun BandBar(weights: List<Float>, colors: List<Color>, height: Int, modifier: Modifier = Modifier) {
    val total = weights.sum()
    Row(
        modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape((height / 2).dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (total <= 0f) {
            Box(Modifier.weight(1f).height(height.dp).background(Tokens.Surface2))
        } else {
            weights.forEachIndexed { i, w ->
                if (w > 0f) Box(Modifier.weight(w).height(height.dp).background(colors.getOrElse(i) { Tokens.Surface2 }))
            }
        }
    }
}

/** Legend entry: coloured dot, name and a mono value. */
@Composable
fun LegendItem(color: Color, name: String, value: String, dotSize: Int = 8, gap: Int = 6) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(dotSize.dp).clip(CircleShape).background(color))
        Text(name, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Normal), color = Tokens.Muted)
        Text(value, style = MonoNumber.copy(fontSize = 11.sp), color = Tokens.TextSoft)
    }
}

/** Title plus one paragraph of explanation ("From the watch", trend notes). */
@Composable
fun NoteCard(title: String, body: String) {
    AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = CardTitleStyle, color = Tokens.Text)
            Text(body, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp), color = Tokens.Muted)
        }
    }
}

/** Dashed-border placeholder line used where a list is empty inside a screen. */
@Composable
fun DashedNotice(text: String) {
    Box(
        Modifier.fillMaxWidth().border(1.dp, Tokens.BorderStrong, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Tokens.Dim, textAlign = TextAlign.Center)
    }
}

/** Header row of a chart card: 13 sp title on the left, mono caption on the right. */
@Composable
fun CardHeader(title: String, caption: String?, modifier: Modifier = Modifier, captionContent: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = CardTitleStyle, color = Tokens.Text)
        if (captionContent != null) captionContent() else if (caption != null) Text(caption, style = MonoCaption, color = Tokens.Muted)
    }
}

/** Small label over a 15 sp mono value, the footer cells of the heart rate card. */
@Composable
fun FooterStat(label: String, value: String, alignEnd: Boolean = false) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Normal), color = Tokens.Muted)
        Text(value, style = MonoNumberLarge.copy(fontWeight = FontWeight.SemiBold, fontFamily = PlexMono), color = Tokens.Text)
    }
}
