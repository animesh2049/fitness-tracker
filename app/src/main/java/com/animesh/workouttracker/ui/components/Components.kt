package com.animesh.workouttracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animesh.workouttracker.ui.theme.MonoStat
import com.animesh.workouttracker.ui.theme.Tokens

@Composable
fun ScreenHeader(eyebrow: String, title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tokens.Muted)
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tokens.Muted, modifier = modifier.padding(horizontal = 4.dp))
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Tokens.Border,
    background: Color = Tokens.Surface,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Int = 56) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(height.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Tokens.Accent, contentColor = Tokens.AccentInk,
            disabledContainerColor = Tokens.Surface2, disabledContentColor = Tokens.Dim
        )
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Int = 48, contentColor: Color = Tokens.Text, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = BorderStroke(1.dp, Tokens.Border),
        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Surface, contentColor = contentColor, disabledContainerColor = Tokens.Surface, disabledContentColor = Tokens.Dim)
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Int = 44, contentColor: Color = Tokens.TextSoft, borderColor: Color = Tokens.BorderStrong) {
    Button(
        onClick = onClick,
        modifier = modifier.height(height.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = contentColor)
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
fun AccentChipButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Accent, contentColor = Tokens.AccentInk)
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun OutlineChipButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, contentColor: Color = Tokens.Text, borderColor: Color = Tokens.Faint) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = contentColor)
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

@Composable
fun PillTag(text: String, color: Color = Tokens.Muted, borderColor: Color = Tokens.BorderStrong) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp)
    )
}

/** Big number with plus and minus, 44 dp hit targets. */
@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.Ground)
            .border(1.dp, Tokens.Border, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Tokens.Muted)
        Text(value, style = MaterialTheme.typography.displaySmall, color = Tokens.Text, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−", onMinus)
            StepButton("+", onPlus)
        }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 52.dp, height = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Tokens.Surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = Tokens.Text)
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String? = null, modifier: Modifier = Modifier, valueColor: Color = Tokens.Text) {
    AppCard(modifier = modifier, padding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Tokens.Muted)
            Text(value, style = MonoStat, color = valueColor)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Tokens.Dim)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Tokens.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Tokens.Muted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Surface,
        titleContentColor = Tokens.Text,
        textContentColor = Tokens.Muted,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(body, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (destructive) Tokens.Danger else Tokens.Accent, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.TextSoft, style = MaterialTheme.typography.labelLarge) }
        }
    )
}

@Composable
fun BottomActionBar(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tokens.Ground)
            .border(width = 1.dp, color = Tokens.Surface2)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun ProgressSegments(total: Int, done: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            val color = when {
                i < done -> Tokens.Accent
                i == current -> Tokens.AccentDim
                else -> Tokens.Surface2
            }
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Tokens.Accent) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Tokens.Surface2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}
