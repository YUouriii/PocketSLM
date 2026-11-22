package com.pocketllm.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketllm.ui.theme.ControlSize
import com.pocketllm.ui.theme.IconSize
import com.pocketllm.ui.theme.Spacing

/** Hairline-bordered card used on all three screens. Visual output is unchanged. */
@Composable
fun ThinCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    padding: Dp? = Spacing.L,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(if (padding != null) Modifier.padding(padding) else Modifier) { content() }
    }
}

/** Small pill for status / fit / capability labels. */
@Composable
fun StatusPill(text: String, active: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (active) scheme.primary else scheme.surfaceVariant,
        border = if (active) null else BorderStroke(1.dp, scheme.outline),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.M, vertical = Spacing.XS),
        )
    }
}

/**
 * The one selectable chip used for filter tabs and option rows.
 * Selected is a filled primary chip; unselected is a hairline-outlined quiet chip.
 */
@Composable
fun AppChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    compact: Boolean = false,
    centerText: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.primary else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, scheme.outline),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .then(if (centerText) Modifier.fillMaxWidth() else Modifier)
                .padding(
                    horizontal = if (compact) 10.dp else Spacing.L,
                    vertical = if (compact) 6.dp else 9.dp,
                ),
            horizontalArrangement = if (centerText) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(if (compact) IconSize.Small else IconSize.Medium),
                )
                Spacer(Modifier.width(if (compact) 6.dp else Spacing.XS))
            }
            Text(
                text = text,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
            )
            if (trailingIcon != null) {
                Spacer(Modifier.width(Spacing.XS))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(if (compact) IconSize.Small else IconSize.Medium),
                )
            }
        }
    }
}

/** Compact icon action with a comfortable touch target. */
@Composable
fun SmallActionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(ControlSize.SmallAction)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.Medium),
        )
    }
}

/** Quiet pill button for secondary actions. */
@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.XL, vertical = Spacing.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Inline notice for status, warnings and recoverable errors. Uses weight and a
 * hairline border instead of colour so it fits the monochrome language.
 */
@Composable
fun StatusNotice(
    title: String,
    detail: String? = null,
    error: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (error) scheme.onSurface.copy(alpha = 0.30f) else scheme.outlineVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(Spacing.M),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (error) Icons.Rounded.Warning else Icons.Rounded.Info,
                contentDescription = null,
                tint = if (error) scheme.onSurface else scheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(IconSize.Medium),
            )
            Spacer(Modifier.width(Spacing.M))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Shared confirmation dialog with an optional leading icon. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val vector = icon
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = scheme.surface,
        tonalElevation = 0.dp,
        icon = if (vector != null) {
            {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.Large),
                )
            }
        } else {
            null
        },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}
