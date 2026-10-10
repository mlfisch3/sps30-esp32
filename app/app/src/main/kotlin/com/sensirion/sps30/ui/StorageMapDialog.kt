package com.sensirion.sps30.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The storage occupancy map: the controller's flash drawn like a seat map.
 *
 * One cell per 512-byte page, the partition's regions in the order they sit on
 * the chip, each with its own write position - so "how far through overwriting
 * the ring has the head got" is read off the picture rather than inferred from
 * a percentage. The cells come from `storage_map`, which the firmware answers
 * by reading every page's own header; nothing here is interpolated from a
 * fraction, and the one thing the dialog adds is the head marker, which is the
 * write position the same message carries.
 *
 * Read-only by construction: the dialog sends only `storage_map`, which walks
 * headers and changes nothing. The Data tab's inventory is a different view of
 * related information - counts per session against what this device holds -
 * and the two deliberately quote different sources: the inventory quotes
 * `storage`'s counters, the map quotes its own cells, and neither invents the
 * other's number.
 *
 * The section list renders whatever arrives, sorted by where it sits on the
 * chip. When the tiered coarse generations land they arrive as more sections
 * and this file draws them unchanged; an id it does not know is labelled by
 * the id itself rather than dropped.
 */

/** How often the open dialog asks again. Closed, nothing asks at all. */
internal const val STORAGE_MAP_REFRESH_MS = 5_000L

/* The palette. Literals rather than theme roles, like the gap fills: these are
 * domain colours the legend and the cells must agree on exactly. */
private val DATA_COLOR = Color(0xFF3BAFCB)
private val ANCHOR_COLOR = Color(0xFF8A63D2)
private val BAD_COLOR = Color(0xFFB0453C)
private val EMPTY_COLOR = Color(0xFF9E9E9E)
private val HEAD_COLOR = Color(0xFFFFB300)

/**
 * How one page state is painted, as data rather than as a branch: the cell in
 * the grid and the swatch in the legend are the same fill through the same
 * painter, and the four become comparable so a test can assert they are
 * pairwise distinct.
 *
 * No two states differ by colour alone. Written is a solid block; empty is an
 * outline with no fill; an anchor is a solid block with a punched-out centre;
 * unreadable is an outline with a slash through it.
 */
internal data class CellFill(
    /** Solid interior, or null for an open cell. */
    val fill: Color?,
    /** Outline, for the states whose interior is not solid. */
    val outline: Color? = null,
    /** A punched-out centre dot, over [fill]. */
    val dot: Color? = null,
    /** A diagonal slash corner to corner. */
    val slash: Color? = null,
)

internal val CELL_FILLS: Map<Int, CellFill> = mapOf(
    Wire.PAGE_STATE_EMPTY to CellFill(fill = null, outline = EMPTY_COLOR.copy(alpha = 0.45f)),
    Wire.PAGE_STATE_DATA to CellFill(fill = DATA_COLOR),
    Wire.PAGE_STATE_ANCHOR to CellFill(fill = ANCHOR_COLOR, dot = Color(0xE6FFFFFF)),
    Wire.PAGE_STATE_BAD to CellFill(
        fill = null,
        outline = BAD_COLOR,
        slash = BAD_COLOR,
    ),
)

/** What the legend says about one state, in the plainest words there are. */
internal val CELL_LEGEND: List<Pair<Int, String>> = listOf(
    Wire.PAGE_STATE_DATA to "holds readings",
    Wire.PAGE_STATE_EMPTY to "empty",
    Wire.PAGE_STATE_ANCHOR to "time anchor",
    Wire.PAGE_STATE_BAD to "unreadable",
)

/**
 * A section's cells resolved to a grid: fixed pitch, rows to fit.
 *
 * Columns come from the width and a target cell size, then the pitch is spread
 * so the grid spans the width exactly - a ragged right edge would read as
 * missing pages. Pure, so the arithmetic that places four and a half thousand
 * cells is testable without a renderer.
 */
internal data class MapGrid(
    val columns: Int,
    val rows: Int,
    val cellPx: Float,
    val pitchPx: Float,
) {
    val heightPx: Float get() = rows * pitchPx
}

internal fun mapGrid(
    pages: Int,
    widthPx: Float,
    targetCellPx: Float,
    gapPx: Float,
): MapGrid {
    if (pages <= 0 || widthPx <= 0f) return MapGrid(1, 0, targetCellPx, targetCellPx + gapPx)
    val columns = floor((widthPx + gapPx) / (targetCellPx + gapPx)).toInt()
        .coerceAtLeast(1)
        .coerceAtMost(pages)
    val rows = ceil(pages / columns.toDouble()).toInt()
    val pitch = (widthPx + gapPx) / columns
    return MapGrid(columns, rows, cellPx = pitch - gapPx, pitchPx = pitch)
}

/** Top-left corner of one page's cell. */
internal fun cellOrigin(grid: MapGrid, page: Int): Offset {
    val column = page % grid.columns
    val row = page / grid.columns
    return Offset(column * grid.pitchPx, row * grid.pitchPx)
}

/** One cell, painted from its fill - the grid and the legend share this. */
internal fun DrawScope.drawCell(fill: CellFill, origin: Offset, side: Float) {
    fill.fill?.let { drawRect(color = it, topLeft = origin, size = Size(side, side)) }
    fill.outline?.let {
        val stroke = (side * 0.16f).coerceAtLeast(1f)
        drawRect(
            color = it,
            topLeft = Offset(origin.x + stroke / 2f, origin.y + stroke / 2f),
            size = Size(side - stroke, side - stroke),
            style = Stroke(width = stroke),
        )
    }
    fill.dot?.let {
        val inset = side * 0.36f
        drawRect(
            color = it,
            topLeft = Offset(origin.x + inset, origin.y + inset),
            size = Size(side - inset * 2f, side - inset * 2f),
        )
    }
    fill.slash?.let {
        drawLine(
            color = it,
            start = Offset(origin.x, origin.y + side),
            end = Offset(origin.x + side, origin.y),
            strokeWidth = (side * 0.22f).coerceAtLeast(1f),
        )
    }
}

/**
 * A whole section: every cell from its own state, then the write position as
 * a ring around the page the next write goes into. The head is drawn last so
 * nothing paints over it, and in its own colour so it cannot be mistaken for
 * a fifth page state.
 */
internal fun DrawScope.drawStorageSection(states: List<Int>, head: Int, grid: MapGrid) {
    for (page in states.indices) {
        val fill = CELL_FILLS[states[page]] ?: continue
        drawCell(fill, cellOrigin(grid, page), grid.cellPx)
    }
    if (head in states.indices) {
        val origin = cellOrigin(grid, head)
        val stroke = (grid.cellPx * 0.34f).coerceAtLeast(1.5f)
        drawRect(
            color = HEAD_COLOR,
            topLeft = Offset(origin.x - stroke / 2f, origin.y - stroke / 2f),
            size = Size(grid.cellPx + stroke, grid.cellPx + stroke),
            style = Stroke(width = stroke),
        )
    }
}

/** The reader's name for a section the wire knows by id. */
internal fun sectionLabel(id: String): String = when (id) {
    Wire.MAP_SECTION_RING -> "Readings ring"
    Wire.MAP_SECTION_ALERT_HEADERS -> "Alert summaries (reserved)"
    Wire.MAP_SECTION_ALERT_BODIES -> "Alert samples (reserved)"
    else -> id
}

/**
 * How far the write position has got, in words the map's own cells back up.
 *
 * The wrap is the number the captain asked to see: once a section has filled,
 * which pass the head is on and how far through overwriting it has got.
 */
internal fun sectionProgress(section: ServerMessage.MapSection): String {
    if (section.pages <= 0) return ""
    val percent = (section.head * 100) / section.pages
    return if (section.wraps == 0) {
        "first pass - write head at page ${section.head} of ${section.pages}"
    } else {
        "pass ${section.wraps + 1} - overwriting, $percent% of the way round"
    }
}

/**
 * The icon beside the battery: a miniature of the map itself, filled to the
 * fraction `storage` already reports. A pressable control, so it gets the
 * outlined container the house rule asks for; the miniature makes no per-page
 * claim - twelve cells filled to a fraction are legible at 22 x 16 dp where
 * four thousand would be a smear.
 */
@Composable
fun StorageMapIcon(
    fraction: Double?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Storage map" },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Canvas(Modifier.padding(horizontal = 8.dp, vertical = 7.dp).size(width = 22.dp, height = 16.dp)) {
            val columns = 4
            val rows = 3
            val gap = size.width * 0.08f
            val cellW = (size.width - gap * (columns - 1)) / columns
            val cellH = (size.height - gap * (rows - 1)) / rows
            val filled = if (fraction == null) 0 else {
                ceil(fraction.coerceIn(0.0, 1.0) * columns * rows).toInt()
            }
            for (i in 0 until columns * rows) {
                val origin = Offset(
                    (i % columns) * (cellW + gap),
                    (i / columns) * (cellH + gap),
                )
                if (i < filled) {
                    drawRect(DATA_COLOR, topLeft = origin, size = Size(cellW, cellH))
                } else {
                    drawRect(
                        EMPTY_COLOR.copy(alpha = 0.45f),
                        topLeft = Offset(origin.x + 0.5f, origin.y + 0.5f),
                        size = Size(cellW - 1f, cellH - 1f),
                        style = Stroke(width = 1f),
                    )
                }
            }
        }
    }
}

/**
 * The popup. Asks for a fresh map when opened and every
 * [STORAGE_MAP_REFRESH_MS] while it stays open; nothing polls when closed.
 */
@Composable
fun StorageMapDialog(
    state: DashboardUiState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(STORAGE_MAP_REFRESH_MS)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Storage map") },
        text = { StorageMapBody(state) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * The body, split out the way [OptionsSheetBody] is so tests can put it up
 * and measure it without the dialog chrome.
 */
@Composable
internal fun StorageMapBody(state: DashboardUiState) {
    val map = state.storageMap
    val storage = state.storage
    val connected = state.connection is ConnectionState.Connected
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        when {
            storage != null && !storage.ready -> Text(
                "The sensor is still bringing its storage up; the map will follow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            map == null -> Text(
                "Asking the sensor which pages hold data…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                if (!connected) {
                    // The tense rule the copy-sessions explainer follows: off a
                    // dead link the map is what the flash held, not what it holds.
                    Text(
                        "As last seen - the sensor is not connected, so this is " +
                            "the map as it was.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                for (section in map.sections.sortedBy { it.start }) {
                    SectionMap(section)
                }
                MapLegend()
                Text(
                    "One cell is one ${map.pageBytes}-byte flash page, drawn in the " +
                        "order the pages sit on the chip. The sensor re-reads every " +
                        "page's header for each refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One section: its name, its write position in words, and its cells. */
@Composable
private fun SectionMap(section: ServerMessage.MapSection) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            sectionLabel(section.id),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            sectionProgress(section),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val grid = mapGrid(
                pages = section.pages,
                widthPx = widthPx,
                targetCellPx = with(density) { 3.5.dp.toPx() },
                gapPx = with(density) { 1.dp.toPx() },
            )
            val heightDp = with(density) { grid.heightPx.toDp() }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(heightDp)
                    .semantics {
                        contentDescription =
                            "${sectionLabel(section.id)}: ${section.pages} pages"
                    },
            ) {
                drawStorageSection(section.states, section.head, grid)
            }
        }
    }
}

/** The key, its swatches painted by the cells' own painter. */
@Composable
private fun MapLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((stateValue, words) in CELL_LEGEND) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.padding(end = 8.dp).size(12.dp)) {
                    CELL_FILLS[stateValue]?.let { drawCell(it, Offset.Zero, size.width) }
                }
                Text(
                    words,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.padding(end = 8.dp).size(12.dp)) {
                drawRect(
                    color = HEAD_COLOR,
                    topLeft = Offset(1f, 1f),
                    size = Size(size.width - 2f, size.height - 2f),
                    style = Stroke(width = 2.5f),
                )
            }
            Text(
                "write position",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
