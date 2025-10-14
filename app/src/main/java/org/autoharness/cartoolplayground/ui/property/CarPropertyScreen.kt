package org.autoharness.cartoolplayground.ui.property

import android.car.hardware.CarPropertyValue
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val DisplayProperty.uniqueKey: String
    get() = "${this.propertyId}-${this.areaId}"

@Composable
fun CarPropertyScreen(carPropertyViewModel: CarPropertyViewModel) {
    val properties by carPropertyViewModel.displayProperties.collectAsState()
    val sortedProperties = remember(properties) {
        properties.sortedBy { it.propertyName }
    }
    val currentSortedProperties by rememberUpdatedState(sortedProperties)

    var highlightedItemKey by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        carPropertyViewModel.propertyUpdateEvent.collect { updatedProp ->
            val index = currentSortedProperties.indexOfFirst {
                it.uniqueKey == updatedProp.uniqueKey
            }
            if (index != -1) {
                val isVisible =
                    listState.layoutInfo.visibleItemsInfo.any { it.key == updatedProp.uniqueKey }
                if (!isVisible) {
                    listState.animateScrollToItem(index)
                }
                highlightedItemKey = updatedProp.uniqueKey
            }
        }
    }
    /* ==== The car property screen layout. ==== */
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            PropertyTableHeader()
            LazyColumn(state = listState) {
                itemsIndexed(
                    items = sortedProperties,
                    key = { _, item -> item.uniqueKey },
                ) { index, property ->

                    PropertyTableRow(
                        property = property,
                        showPropertyName = index == 0 || sortedProperties[index - 1].propertyName != property.propertyName,
                        isHighlighted = (property.uniqueKey == highlightedItemKey),
                        onHighlightFinished = { highlightedItemKey = null },
                    )

                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PropertyTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Property Name", modifier = Modifier.weight(2f), fontWeight = FontWeight.Bold)
        Text("Area ID", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text("Value / Status", modifier = Modifier.weight(2f), fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PropertyTableRow(
    property: DisplayProperty,
    showPropertyName: Boolean,
    isHighlighted: Boolean,
    onHighlightFinished: () -> Unit,
) {
    val propertyValue = property.propertyValue.value
    val (displayText, displayColor) = formatCarPropertyValue(propertyValue)

    val defaultBackgroundColor = MaterialTheme.colorScheme.surface
    val backgroundColor = remember { Animatable(defaultBackgroundColor) }
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    LaunchedEffect(isHighlighted) {
        if (!isHighlighted) return@LaunchedEffect

        backgroundColor.snapTo(highlightColor)
        kotlinx.coroutines.delay(150)
        backgroundColor.animateTo(
            defaultBackgroundColor,
            animationSpec = tween(durationMillis = 150),
        )
        onHighlightFinished()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor.value)
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (showPropertyName) property.propertyName else "",
            modifier = Modifier.weight(2f),
            fontWeight = if (showPropertyName) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(property.areaId.toString(), modifier = Modifier.weight(1f))
        Text(
            text = displayText,
            color = displayColor,
            modifier = Modifier.weight(2f),
        )
    }
}

/**
 * Formats a CarPropertyValue for display, handling different statuses.
 */
@Composable
private fun formatCarPropertyValue(value: CarPropertyValue<*>?): Pair<String, Color> {
    if (value == null) {
        return "Loading..." to Color.DarkGray
    }

    return when (value.propertyStatus) {
        CarPropertyValue.STATUS_AVAILABLE -> {
            val formattedValue = value.value?.toString() ?: "null"
            formattedValue to MaterialTheme.colorScheme.onSurface
        }

        CarPropertyValue.STATUS_UNAVAILABLE -> "Unavailable" to Color.Yellow
        CarPropertyValue.STATUS_ERROR -> "Error" to Color.Red
        else -> "Unknown Status" to Color.DarkGray
    }
}
