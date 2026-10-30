package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GpsTelemetry
import com.example.data.model.LatLngPoint
import com.example.ui.theme.CockpitBlack
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitCard
import com.example.ui.theme.HudMuted
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.OrbitronFontFamily

@Composable
fun CompassMiniMapHud(
    telemetry: GpsTelemetry,
    modifier: Modifier = Modifier
) {
    val animatedBearing by animateFloatAsState(
        targetValue = telemetry.bearingDegrees,
        label = "bearing"
    )

    // Sleek, borderless circular compass radar without outer rectangular header box
    Box(
        modifier = modifier
            .size(114.dp)
            .background(CockpitBlack.copy(alpha = 0.70f), CircleShape)
            .border(1.5.dp, NeonCyan.copy(alpha = 0.65f), CircleShape)
            .testTag("compass_minimap_hud"),
        contentAlignment = Alignment.Center
    ) {
        // Radar concentric circles & live route trail
        RadarRouteCanvas(
            points = telemetry.locationHistory,
            bearing = animatedBearing,
            modifier = Modifier.fillMaxSize()
        )

        // North Pointer & Cardinal Points
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // North indicator
            Text(
                text = "N",
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                color = NeonRed,
                fontFamily = OrbitronFontFamily
            )

            // Center Heading Pointer
            Icon(
                imageVector = Icons.Default.Navigation,
                contentDescription = "Bearing",
                tint = NeonGreen,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(animatedBearing)
            )

            Text(
                text = "S",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = HudMuted,
                fontFamily = OrbitronFontFamily
            )
        }

        // East & West Indicators
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "W", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HudMuted)
            Text(text = "E", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HudMuted)
        }
    }
}

@Composable
private fun RadarRouteCanvas(
    points: List<LatLngPoint>,
    bearing: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.padding(8.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f

        // Draw concentric radar circles
        drawCircle(
            color = Color(0xFF1E2B40).copy(alpha = 0.65f),
            radius = maxRadius * 0.45f,
            center = center,
            style = Stroke(1.2.dp.toPx())
        )
        drawCircle(
            color = Color(0xFF1E2B40).copy(alpha = 0.65f),
            radius = maxRadius * 0.82f,
            center = center,
            style = Stroke(1.2.dp.toPx())
        )

        // Draw crosshairs
        drawLine(
            color = Color(0xFF1E2B40).copy(alpha = 0.45f),
            start = Offset(center.x, 0f),
            end = Offset(center.x, size.height),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color(0xFF1E2B40).copy(alpha = 0.45f),
            start = Offset(0f, center.y),
            end = Offset(size.width, center.y),
            strokeWidth = 1.dp.toPx()
        )

        // Draw dynamic live route breadcrumb trail
        if (points.size >= 2) {
            val lastPt = points.last()
            val path = Path()

            val routeOffsets = points.takeLast(30).map { pt ->
                val dLon = (pt.lon - lastPt.lon) * 12000f
                val dLat = (pt.lat - lastPt.lat) * 12000f
                Offset(
                    (center.x + dLon.toFloat()).coerceIn(4f, size.width - 4f),
                    (center.y - dLat.toFloat()).coerceIn(4f, size.height - 4f)
                )
            }

            path.moveTo(routeOffsets.first().x, routeOffsets.first().y)
            for (i in 1 until routeOffsets.size) {
                path.lineTo(routeOffsets[i].x, routeOffsets[i].y)
            }

            // Glow line effect
            drawPath(
                path = path,
                color = NeonGreen.copy(alpha = 0.35f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )

            // Sharp line
            drawPath(
                path = path,
                color = NeonGreen,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}
